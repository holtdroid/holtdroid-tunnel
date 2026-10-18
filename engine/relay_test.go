package engine

import (
	"errors"
	"io"
	"net"
	"os"
	"testing"
	"time"
)

func tcpPair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		conn, _ := listener.Accept()
		accepted <- conn
	}()
	dialed, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	return dialed, <-accepted
}

func TestRelayKeepsResponseAfterClientHalfClose(t *testing.T) {
	client, local := tcpPair(t)
	remote, server := tcpPair(t)
	done := make(chan error, 1)
	go func() { done <- relay(local, remote, time.Second) }()

	go func() {
		request := make([]byte, 3)
		if _, err := io.ReadFull(server, request); err != nil {
			t.Error(err)
			return
		}
		server.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
		if _, err := server.Read(make([]byte, 1)); !errors.Is(err, os.ErrDeadlineExceeded) {
			t.Errorf("server saw %v instead of an open stream", err)
		}
		server.Write([]byte("response"))
		server.Close()
	}()

	client.Write([]byte("req"))
	client.(*net.TCPConn).CloseWrite()
	response, err := io.ReadAll(client)
	if err != nil {
		t.Fatal(err)
	}
	if string(response) != "response" {
		t.Fatalf("got %q", response)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestRelayTimesOutIdleHalfClosedStream(t *testing.T) {
	client, local := tcpPair(t)
	remote, server := tcpPair(t)
	defer server.Close()
	done := make(chan error, 1)
	go func() { done <- relay(local, remote, 100*time.Millisecond) }()
	client.(*net.TCPConn).CloseWrite()
	select {
	case err := <-done:
		if !errors.Is(err, os.ErrDeadlineExceeded) {
			t.Fatalf("got %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("relay did not time out")
	}
	if _, err := io.ReadAll(client); err != nil {
		t.Fatal(err)
	}
}
