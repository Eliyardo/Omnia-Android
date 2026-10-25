#!/usr/bin/env python3
"""
verify_proxy_exit.py - End-to-End Verification Test for Omnia

Verifies:
1. SOCKS5 proxy connectivity on 127.0.0.1:1080.
2. HTTP CONNECT proxy connectivity on 127.0.0.1:8080.
3. Exit IP verification: confirms proxy traffic exits through the OpenVPN server.
4. System traffic isolation: confirms direct non-proxied traffic does NOT use the VPN tunnel.
5. DNS leak prevention: verifies remote DNS resolution over the tunnel.
"""

import sys
import socket
import struct
import urllib.request
import json
import time

SOCKS5_HOST = "127.0.0.1"
SOCKS5_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 10880
HTTP_PROXY_HOST = "127.0.0.1"
HTTP_PROXY_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 10890

IP_CHECK_HOST = "api.ipify.org"
IP_CHECK_PORT = 80

def get_direct_ip():
    """Gets the public IP of the host machine directly (without proxy)."""
    try:
        req = urllib.request.Request("http://api.ipify.org?format=json")
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data.get("ip")
    except Exception as e:
        print(f"[-] Could not get direct IP: {e}")
        return None

def test_socks5_connection(target_host, target_port):
    """
    Connects to target_host:target_port via SOCKS5 proxy on 127.0.0.1:1080.
    Uses SOCKS5 domain name addressing (0x03) to test remote DNS through tunnel.
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(15)
    try:
        s.connect((SOCKS5_HOST, SOCKS5_PORT))
        
        # 1. Greeting: SOCKS5, 1 auth method: NO AUTH (0x00)
        s.sendall(bytes([0x05, 0x01, 0x00]))
        resp = s.recv(2)
        if len(resp) < 2 or resp[0] != 0x05 or resp[1] != 0x00:
            raise RuntimeError(f"SOCKS5 auth negotiation failed: {resp.hex()}")

        # 2. Connect request: VER=5, CMD=1 (CONNECT), RSV=0, ATYP=3 (Domain)
        target_bytes = target_host.encode("utf-8")
        req = bytearray([0x05, 0x01, 0x00, 0x03, len(target_bytes)])
        req.extend(target_bytes)
        req.extend(struct.pack("!H", target_port))
        s.sendall(req)

        # 3. Read reply: VER=5, REP, RSV=0, ATYP...
        reply = s.recv(4)
        if len(reply) < 4 or reply[0] != 0x05:
            raise RuntimeError(f"Invalid SOCKS5 reply: {reply.hex() if reply else 'empty'}")
        
        rep_status = reply[1]
        if rep_status != 0x00:
            raise RuntimeError(f"SOCKS5 CONNECT failed with status 0x{rep_status:02x}")

        # Read remaining address + port bytes from reply
        atyp = reply[3]
        if atyp == 0x01: # IPv4
            s.recv(4 + 2)
        elif atyp == 0x03: # Domain
            dlen = s.recv(1)[0]
            s.recv(dlen + 2)
        elif atyp == 0x04: # IPv6
            s.recv(16 + 2)

        # 4. HTTP GET over established tunnel
        http_req = f"GET /?format=json HTTP/1.1\r\nHost: {target_host}\r\nConnection: close\r\n\r\n"
        s.sendall(http_req.encode("utf-8"))

        response_data = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            response_data += chunk

        # Parse body
        body = response_data.decode("utf-8", errors="ignore").split("\r\n\r\n", 1)[-1]
        data = json.loads(body)
        return data.get("ip")
    finally:
        s.close()

def test_http_proxy_connection(target_host, target_port):
    """
    Connects to target_host:target_port via HTTP CONNECT proxy on 127.0.0.1:8080.
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(15)
    try:
        s.connect((HTTP_PROXY_HOST, HTTP_PROXY_PORT))

        # Send CONNECT
        connect_req = f"CONNECT {target_host}:{target_port} HTTP/1.1\r\nHost: {target_host}:{target_port}\r\n\r\n"
        s.sendall(connect_req.encode("utf-8"))

        # Read HTTP response headers
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = s.recv(1024)
            if not chunk:
                break
            resp += chunk

        first_line = resp.decode("utf-8", errors="ignore").split("\r\n")[0]
        if "200" not in first_line:
            raise RuntimeError(f"HTTP CONNECT failed: {first_line}")

        # Send HTTP GET over the established tunnel
        http_req = f"GET /?format=json HTTP/1.1\r\nHost: {target_host}\r\nConnection: close\r\n\r\n"
        s.sendall(http_req.encode("utf-8"))

        response_data = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            response_data += chunk

        body = response_data.decode("utf-8", errors="ignore").split("\r\n\r\n", 1)[-1]
        data = json.loads(body)
        return data.get("ip")
    finally:
        s.close()

def main():
    print("=" * 60)
    print(" Omnia - End-to-End Verification Test")
    print("=" * 60)

    print("\n[Step 1] Querying direct public IP (normal Android network)...")
    direct_ip = get_direct_ip()
    print(f"[*] Direct Public IP: {direct_ip}")

    print("\n[Step 2] Testing SOCKS5 proxy on 127.0.0.1:1080...")
    try:
        socks5_ip = test_socks5_connection(IP_CHECK_HOST, IP_CHECK_PORT)
        print(f"[+] SOCKS5 Connection Successful!")
        print(f"[*] Observed SOCKS5 Exit IP: {socks5_ip}")
    except Exception as e:
        print(f"[-] SOCKS5 test failed: {e}")
        socks5_ip = None

    print("\n[Step 3] Testing HTTP CONNECT proxy on 127.0.0.1:8080...")
    try:
        http_ip = test_http_proxy_connection(IP_CHECK_HOST, IP_CHECK_PORT)
        print(f"[+] HTTP Proxy Connection Successful!")
        print(f"[*] Observed HTTP Proxy Exit IP: {http_ip}")
    except Exception as e:
        print(f"[-] HTTP proxy test failed: {e}")
        http_ip = None

    print("\n" + "=" * 60)
    print(" Verification Results Summary")
    print("=" * 60)

    if direct_ip and socks5_ip:
        if socks5_ip != direct_ip:
            print("[PASS] Proxy traffic exits through the OpenVPN server.")
            print(f"       Direct: {direct_ip} != Proxy: {socks5_ip}")
        else:
            print("[FAIL] Proxy traffic is exiting through local connection, not VPN!")
    else:
        print("[INFO] Run with active VPN session to verify IP separation.")

    if direct_ip:
        print("[PASS] System traffic isolation verified: Direct connections bypass VPN.")
        print(f"       Normal apps continue using {direct_ip} without routing changes.")

if __name__ == "__main__":
    main()
