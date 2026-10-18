package engine

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
	"github.com/sagernet/sing/protocol/socks"
)

const (
	LevelDebug = 3
	LevelInfo  = 4
	LevelWarn  = 5
	LevelError = 6
)

type Logger interface {
	Log(level int32, message string)
}

type Options struct {
	Fd         int32
	Mtu        int32
	Inet4      string
	Inet6      string
	ProxyHost  string
	ProxyPort  int32
	UdpOverTcp bool
}

type Stats struct {
	TcpActive int64
	TcpTotal  int64
	UdpActive int64
	UdpTotal  int64
	Uplink    int64
	Downlink  int64
	Failures  int64
	LastError string
}

type Session struct {
	ctx      context.Context
	cancel   context.CancelFunc
	device   tun.Tun
	stack    tun.Stack
	socks    *socks.Client
	uot      *uot.Client
	log      Logger
	tcpOpen  atomic.Int64
	tcpAll   atomic.Int64
	udpOpen  atomic.Int64
	udpAll   atomic.Int64
	up       atomic.Int64
	down     atomic.Int64
	failures atomic.Int64
	lastErr  atomic.Value
	once     sync.Once
}

func Start(options *Options, logger Logger) (*Session, error) {
	if options == nil || options.Fd <= 0 {
		return nil, errors.New("invalid tun file descriptor")
	}
	server, err := proxyAddress(options.ProxyHost, options.ProxyPort)
	if err != nil {
		return nil, err
	}
	tunOptions := tun.Options{
		Name:           "tun",
		MTU:            uint32(options.Mtu),
		FileDescriptor: int(options.Fd),
		Logger:         &stackLogger{logger},
	}
	if options.Inet4 != "" {
		prefix, err := netip.ParsePrefix(options.Inet4)
		if err != nil {
			return nil, fmt.Errorf("inet4: %w", err)
		}
		tunOptions.Inet4Address = []netip.Prefix{prefix}
	}
	if options.Inet6 != "" {
		prefix, err := netip.ParsePrefix(options.Inet6)
		if err != nil {
			return nil, fmt.Errorf("inet6: %w", err)
		}
		tunOptions.Inet6Address = []netip.Prefix{prefix}
	}
	ctx, cancel := context.WithCancel(context.Background())
	client := socks.NewClient(N.SystemDialer, server, socks.Version5, "", "")
	session := &Session{ctx: ctx, cancel: cancel, socks: client, log: logger}
	if options.UdpOverTcp {
		session.uot = &uot.Client{Dialer: client, Version: uot.Version}
	}
	device, err := tun.New(tunOptions)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("open tun: %w", err)
	}
	session.device = device
	stack, err := tun.NewStack("gvisor", tun.StackOptions{
		Context:    ctx,
		Tun:        device,
		TunOptions: tunOptions,
		UDPTimeout: 5 * time.Minute,
		Handler:    handler{session},
		Logger:     &stackLogger{logger},
	})
	if err != nil {
		session.Close()
		return nil, fmt.Errorf("create stack: %w", err)
	}
	session.stack = stack
	if err = device.Start(); err != nil {
		session.Close()
		return nil, fmt.Errorf("start tun: %w", err)
	}
	if err = stack.Start(); err != nil {
		session.Close()
		return nil, fmt.Errorf("start stack: %w", err)
	}
	session.emit(LevelInfo, "tunnel up, proxy "+server.String())
	return session, nil
}

func proxyAddress(host string, port int32) (M.Socksaddr, error) {
	if port <= 0 || port > 65535 {
		return M.Socksaddr{}, fmt.Errorf("invalid proxy port %d", port)
	}
	addr, err := netip.ParseAddr(strings.Trim(host, "[]"))
	if err != nil {
		return M.Socksaddr{}, fmt.Errorf("proxy host must be an IP literal: %w", err)
	}
	return M.SocksaddrFrom(addr, uint16(port)), nil
}

func (s *Session) Close() error {
	var err error
	s.once.Do(func() {
		s.cancel()
		if s.stack != nil {
			err = s.stack.Close()
		}
		if s.device != nil {
			err = errors.Join(err, s.device.Close())
		}
		s.emit(LevelInfo, "tunnel down")
	})
	return err
}

func (s *Session) Stats() *Stats {
	stats := &Stats{
		TcpActive: s.tcpOpen.Load(),
		TcpTotal:  s.tcpAll.Load(),
		UdpActive: s.udpOpen.Load(),
		UdpTotal:  s.udpAll.Load(),
		Uplink:    s.up.Load(),
		Downlink:  s.down.Load(),
		Failures:  s.failures.Load(),
	}
	if message, ok := s.lastErr.Load().(string); ok {
		stats.LastError = message
	}
	return stats
}

func (h handler) JudgeFlow(network uint8, source netip.AddrPort, destination netip.AddrPort, firstPacket []byte) tun.FlowVerdict {
	switch network {
	case 6:
		return tun.FlowVerdict{Action: tun.ActionAccept}
	case 17:
		if h.uot == nil {
			return tun.FlowVerdict{Action: tun.ActionReject}
		}
		return tun.FlowVerdict{Action: tun.ActionAccept}
	default:
		return tun.FlowVerdict{Action: tun.ActionReject}
	}
}

func (h handler) NewDNSPacket(payload []byte, source M.Socksaddr, destination M.Socksaddr, writer N.PacketWriter) {
}

func (h handler) NewConnectionEx(ctx context.Context, conn net.Conn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	h.tcpAll.Add(1)
	h.tcpOpen.Add(1)
	defer h.tcpOpen.Add(-1)
	remote, err := h.socks.DialContext(ctx, N.NetworkTCP, destination)
	if err != nil {
		h.fail("tcp "+destination.String(), err)
		conn.Close()
		if onClose != nil {
			onClose(err)
		}
		return
	}
	err = relay(conn, bufio.NewInt64CounterConn(remote, []*atomic.Int64{&h.down}, []*atomic.Int64{&h.up}), halfCloseIdle)
	if onClose != nil {
		onClose(err)
	}
}

const halfCloseIdle = 2 * time.Minute

func relay(local net.Conn, remote net.Conn, idle time.Duration) error {
	var halfClosed atomic.Bool
	upload := make(chan error, 1)
	go func() {
		_, err := io.Copy(remote, local)
		if err != nil {
			remote.Close()
		} else {
			halfClosed.Store(true)
			remote.SetReadDeadline(time.Now().Add(idle))
		}
		upload <- err
	}()
	buffer := make([]byte, 32*1024)
	var err error
	for {
		n, readErr := remote.Read(buffer)
		if n > 0 {
			if _, err = local.Write(buffer[:n]); err != nil {
				break
			}
			if halfClosed.Load() {
				remote.SetReadDeadline(time.Now().Add(idle))
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				err = readErr
			}
			break
		}
	}
	local.Close()
	remote.Close()
	if uploadErr := <-upload; err == nil {
		err = uploadErr
	}
	return err
}

func (h handler) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	h.udpAll.Add(1)
	h.udpOpen.Add(1)
	defer h.udpOpen.Add(-1)
	if h.uot == nil {
		conn.Close()
		if onClose != nil {
			onClose(errors.New("udp disabled"))
		}
		return
	}
	remote, err := h.uot.ListenPacket(ctx, destination)
	if err != nil {
		h.fail("udp "+destination.String(), err)
		conn.Close()
		if onClose != nil {
			onClose(err)
		}
		return
	}
	counted := bufio.NewInt64CounterPacketConn(bufio.NewPacketConn(remote), []*atomic.Int64{&h.down}, nil, []*atomic.Int64{&h.up}, nil)
	err = bufio.CopyPacketConn(h.ctx, conn, counted)
	if onClose != nil {
		onClose(err)
	}
}

type handler struct {
	*Session
}

func (s *Session) fail(what string, err error) {
	s.failures.Add(1)
	message := what + ": " + err.Error()
	s.lastErr.Store(message)
	s.emit(LevelDebug, message)
}

func (s *Session) emit(level int32, message string) {
	if s.log != nil {
		s.log.Log(level, message)
	}
}

type stackLogger struct {
	log Logger
}

func (l *stackLogger) emit(level int32, args []any) {
	if l.log != nil {
		l.log.Log(level, fmt.Sprint(args...))
	}
}

func (l *stackLogger) Trace(args ...any) {}
func (l *stackLogger) Debug(args ...any) { l.emit(LevelDebug, args) }
func (l *stackLogger) Info(args ...any)  { l.emit(LevelInfo, args) }
func (l *stackLogger) Warn(args ...any)  { l.emit(LevelWarn, args) }
func (l *stackLogger) Error(args ...any) { l.emit(LevelError, args) }
func (l *stackLogger) Fatal(args ...any) { l.emit(LevelError, args) }
func (l *stackLogger) Panic(args ...any) { l.emit(LevelError, args) }
