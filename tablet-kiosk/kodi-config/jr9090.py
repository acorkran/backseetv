import socket, json, sys

def call(method, params=None, timeout=20):
    s = socket.create_connection(('127.0.0.1', 9090), timeout=timeout)
    req = {"jsonrpc": "2.0", "id": "me", "method": method}
    if params is not None:
        req["params"] = params
    s.sendall(json.dumps(req).encode())
    s.settimeout(timeout)
    buf = ""
    dec = json.JSONDecoder()
    while True:
        try:
            chunk = s.recv(65536).decode('utf-8', 'ignore')
        except socket.timeout:
            break
        if not chunk:
            break
        buf += chunk
        idx = 0
        while idx < len(buf):
            sub = buf[idx:].lstrip()
            if not sub:
                idx = len(buf); break
            try:
                obj, consumed = dec.raw_decode(sub)
            except ValueError:
                break
            lead = len(buf[idx:]) - len(sub)
            idx = idx + lead + consumed
            if isinstance(obj, dict) and obj.get('id') == 'me':
                s.close(); return obj
        buf = buf[idx:]
    s.close(); return None

if __name__ == '__main__':
    method = sys.argv[1]
    params = json.loads(sys.argv[2]) if len(sys.argv) > 2 else None
    r = call(method, params)
    print(json.dumps(r) if r is not None else "NULL")
