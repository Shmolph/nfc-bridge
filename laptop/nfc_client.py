#!/usr/bin/env python3
"""
Laptop client for the NFC Bridge Android app.

Works like "Advanced NFC commands" in NFC Tools: you type raw hex, the phone sends it
to the card over NfcA and the reply comes back here. The phone adds the CRC itself.

Connect over USB (needs adb and USB debugging):
    python nfc_client.py --adb --pin 1234
or over Wi-Fi (phone and laptop on the same network, use the IP shown in the app):
    python nfc_client.py --host 192.168.1.50 --pin 1234

One-shot commands:
    python nfc_client.py --adb --pin 1234 -c "info" -c "read 8"
"""
import argparse
import socket
import subprocess
import sys

PORT = 8765
HELP = """Commands:
  info                  UID, ATQA, SAK of the card on the phone
  read N                read 4 pages starting at page N (Ultralight/NTAG: 30 NN)
  dump [START END]      read pages START..END (default 0 15)
  write N AABBCCDD      write one 4-byte page (A2 NN data)
  text N some words     write ASCII text from page N, 4 bytes per page, zero padded
  <hex bytes>           anything else made of hex is sent as a raw command, e.g. 30 08
  help, quit
Pages 0-3 hold the UID, lock bits and one-time-programmable bits. Writing them can
brick the card, so you will be asked to confirm.
"""


class Bridge:
    def __init__(self, host, port, pin, timeout=15):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.f = self.sock.makefile("rw", encoding="ascii", newline="\n")
        ok, msg = self.send("AUTH " + pin)
        if not ok:
            raise SystemExit(f"Phone rejected the PIN: {msg}")

    def send(self, line):
        self.f.write(line + "\n")
        self.f.flush()
        reply = self.f.readline()
        if not reply:
            raise ConnectionError("The phone closed the connection.")
        reply = reply.strip()
        if reply.startswith("OK"):
            return True, reply[2:].strip()
        return False, reply[3:].strip() if reply.startswith("ERR") else reply

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def parse_hex(text):
    clean = text.lower().replace("0x", "")
    for ch in " :,-":
        clean = clean.replace(ch, "")
    if not clean or len(clean) % 2 or any(c not in "0123456789abcdef" for c in clean):
        return None
    return bytes.fromhex(clean)


def fmt(b):
    return " ".join(f"{x:02X}" for x in b)


def ascii_of(b):
    return "".join(chr(x) if 32 <= x < 127 else "." for x in b)


def confirm_low_page(page):
    print(f"Page {page} holds the UID, lock bits or OTP bits. A bad write here can")
    print("permanently lock or brick the card and cannot be undone.")
    return input("Type 'yes' to continue: ").strip().lower() == "yes"


def raw(bridge, data):
    if len(data) >= 2 and data[0] in (0xA2, 0xA0) and data[1] < 4:
        if not confirm_low_page(data[1]):
            print("Cancelled.")
            return None
    ok, msg = bridge.send(fmt(data))
    if not ok:
        print("ERROR:", msg)
        return None
    resp = parse_hex(msg) if msg else b""
    if resp == b"\x0a":
        print("ACK (0A) - command accepted")
    elif len(resp) == 1 and resp[0] != 0x0A:
        print(f"Short reply {fmt(resp)} - likely a NAK (locked page, wrong password or bad command)")
    else:
        print("<", fmt(resp), "|", ascii_of(resp), "|" if resp else "")
    return resp


def read_pages(bridge, start):
    ok, msg = bridge.send(f"30 {start:02X}")
    if not ok:
        return None, msg
    data = parse_hex(msg) if msg else b""
    return data, None


def show_pages(start, data):
    for i in range(0, len(data) - len(data) % 4, 4):
        chunk = data[i:i + 4]
        print(f"  page {start + i // 4:3d}: {fmt(chunk)}   {ascii_of(chunk)}")


def handle(bridge, line):
    line = line.strip()
    if not line:
        return True
    parts = line.split()
    cmd = parts[0].lower()

    if cmd in ("quit", "exit", "q"):
        return False
    if cmd in ("help", "?"):
        print(HELP)
    elif cmd == "info":
        ok, msg = bridge.send("INFO")
        print(msg if ok else "ERROR: " + msg)
    elif cmd == "read" and len(parts) == 2:
        page = int(parts[1], 0)
        data, err = read_pages(bridge, page)
        if err:
            print("ERROR:", err)
        elif len(data) == 16:
            show_pages(page, data)
        else:
            print("Short reply:", fmt(data), "(likely a NAK; page may be out of range or protected)")
    elif cmd == "dump":
        start, end = (int(parts[1], 0), int(parts[2], 0)) if len(parts) == 3 else (0, 15)
        for page in range(start, end + 1, 4):
            data, err = read_pages(bridge, page)
            if err:
                print(f"  page {page:3d}: ERROR {err}")
                break
            if len(data) != 16:
                print(f"  page {page:3d}: short reply {fmt(data)} (stopping)")
                break
            show_pages(page, data[: min(16, (end - page + 1) * 4)])
    elif cmd == "write" and len(parts) >= 3:
        data = parse_hex("".join(parts[2:]))
        if data is None or len(data) != 4:
            print("Give exactly 4 bytes of hex, e.g. write 8 68656C6C")
        else:
            raw(bridge, bytes([0xA2, int(parts[1], 0)]) + data)
    elif cmd == "text" and len(parts) >= 3:
        page = int(parts[1], 0)
        payload = line.split(None, 2)[2].encode("utf-8")
        payload += b"\x00" * (-len(payload) % 4)
        if page < 4 and not confirm_low_page(page):
            print("Cancelled.")
            return True
        for i in range(0, len(payload), 4):
            ok, msg = bridge.send(fmt(bytes([0xA2, page + i // 4]) + payload[i:i + 4]))
            if not ok:
                print(f"ERROR writing page {page + i // 4}: {msg}")
                return True
        print(f"Wrote {len(payload)} bytes to pages {page}-{page + len(payload) // 4 - 1}. "
              f"Check with: read {page}")
    else:
        data = parse_hex(line)
        if data is None:
            print("Not a command or valid hex. Type 'help'.")
        else:
            raw(bridge, data)
    return True


def main():
    ap = argparse.ArgumentParser(description="NFC Bridge laptop client")
    ap.add_argument("--host", default="127.0.0.1", help="phone IP (shown in the app)")
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--pin", required=True, help="PIN shown in the app")
    ap.add_argument("--adb", action="store_true", help="run 'adb forward' and connect over USB")
    ap.add_argument("-c", "--cmd", action="append", help="run a command and exit (repeatable)")
    args = ap.parse_args()

    host = args.host
    if args.adb:
        try:
            subprocess.run(["adb", "forward", f"tcp:{args.port}", f"tcp:{args.port}"],
                           check=True, capture_output=True)
        except FileNotFoundError:
            sys.exit("adb not found. Install Android platform-tools or use --host with the phone's IP.")
        except subprocess.CalledProcessError as e:
            sys.exit("adb forward failed: " + e.stderr.decode(errors="replace"))
        host = "127.0.0.1"

    try:
        bridge = Bridge(host, args.port, args.pin)
    except OSError as e:
        sys.exit(f"Could not connect to {host}:{args.port} ({e}). Is the app open on the phone?")

    try:
        if args.cmd:
            for c in args.cmd:
                print(f"> {c}")
                handle(bridge, c)
            return
        print("Connected. Type 'help' for commands.")
        while True:
            try:
                line = input("nfc> ")
            except (EOFError, KeyboardInterrupt):
                print()
                break
            if not handle(bridge, line):
                break
    finally:
        bridge.close()


if __name__ == "__main__":
    main()
