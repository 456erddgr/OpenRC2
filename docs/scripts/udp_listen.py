import socket, sys, time
port = int(sys.argv[1]) if len(sys.argv) > 1 else 8888
dur = float(sys.argv[2]) if len(sys.argv) > 2 else 6.0
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('0.0.0.0', port))
s.settimeout(0.5)
n = 0; bad = 0; first = None; addrs = set(); t0 = time.time()
while time.time() - t0 < dur:
    try:
        d, a = s.recvfrom(2048)
    except socket.timeout:
        continue
    n += 1; addrs.add(a[0])
    if first is None:
        first = d
    if len(d) != 26 or d[0] != 0xC8 or d[1] != 0x18 or d[2] != 0x16:
        bad += 1
print('packets=%d bad=%d from=%s' % (n, bad, ','.join(sorted(addrs)) or '-'))
if first:
    print('hex=' + ' '.join('%02x' % b for b in first))
    # decode crc8 poly 0xd5 over bytes 2..24
    def crc8(buf):
        c = 0
        for b in buf:
            c ^= b
            for _ in range(8):
                c = ((c << 1) ^ 0xD5) & 0xFF if (c & 0x80) else (c << 1) & 0xFF
        return c
    calc = crc8(first[2:25])
    print('crc_ok=%s (calc=%d frame=%d)' % (calc == first[25], calc, first[25]))
    bits = 0
    for i in range(22):
        bits |= first[3+i] << (8*i)
    ch = [(bits >> (11*k)) & 0x7FF for k in range(16)]
    print('channels=' + ' '.join(str(c) for c in ch))
