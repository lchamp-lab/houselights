"""NFS over TCP on port 2049, forwarded to unfsd on 127.0.0.1:2050, each RPC reply sent on in one record fragment.

A NAS's NFS server (Linux's knfsd on a Synology, a QNAP, TrueNAS SCALE, Unraid) sends a reply as one fragment.
unfsd, on libtirpc, splits a reply above 64 KB into fragments of 64 KB (RFC 5531, section 11), as the nfs-fragments
service sends them (README, NFS replies in fragments): this answers as the NAS does, so that the app's tests read
both. Calls go to unfsd unchanged. Once listening, it registers NFS version 3 over TCP at 2049 with rpcbind.
"""
import asyncio
import socket
import struct

PORT = 2049
UNFSD = ("127.0.0.1", 2050)
LAST = 0x80000000


async def calls(client: asyncio.StreamReader, unfsd: asyncio.StreamWriter) -> None:
    """The client's calls, as they come."""
    while data := await client.read(65536):
        unfsd.write(data)
        await unfsd.drain()


async def replies(unfsd: asyncio.StreamReader, client: asyncio.StreamWriter) -> None:
    """unfsd's replies, the fragments of each joined into one."""
    while True:
        reply = bytearray()
        last = False
        while not last:
            (mark,) = struct.unpack(">I", await unfsd.readexactly(4))
            last = bool(mark & LAST)
            reply += await unfsd.readexactly(mark & ~LAST)
        client.write(struct.pack(">I", LAST | len(reply)) + reply)
        await client.drain()


async def connection(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter) -> None:
    try:
        unfsd_reader, unfsd_writer = await asyncio.open_connection(*UNFSD)
    except OSError:
        client_writer.close()
        return
    directions = [
        asyncio.create_task(calls(client_reader, unfsd_writer)),
        asyncio.create_task(replies(unfsd_reader, client_writer)),
    ]
    # Either side hanging up ends both.
    await asyncio.wait(directions, return_when=asyncio.FIRST_COMPLETED)
    for direction in directions:
        direction.cancel()
    # A hang-up ends a direction with an error: it is the end of the connection, nothing to report.
    await asyncio.gather(*directions, return_exceptions=True)
    for writer in (client_writer, unfsd_writer):
        writer.close()


def register() -> None:
    """PMAPPROC_SET of NFS (100003) version 3 over TCP at PORT, a call rpcbind accepts from this machine alone."""
    with socket.create_connection(("127.0.0.1", 111)) as rpcbind:
        no_auth = struct.pack(">IIII", 0, 0, 0, 0)
        mapping = struct.pack(">IIII", 100003, 3, socket.IPPROTO_TCP, PORT)
        call = struct.pack(">IIIIII", 1, 0, 2, 100000, 2, 1) + no_auth + mapping
        rpcbind.sendall(struct.pack(">I", LAST | len(call)) + call)
        answer = rpcbind.recv(64)
        # Record mark, xid, REPLY, MSG_ACCEPTED, verifier (flavour, length), SUCCESS, then the boolean result.
        if len(answer) < 32 or struct.unpack(">I", answer[28:32])[0] != 1:
            raise SystemExit("rpcbind refused NFS's registration at port %d" % PORT)


async def main() -> None:
    server = await asyncio.start_server(connection, "0.0.0.0", PORT)
    register()
    print("NFS on port %d, each reply in one fragment" % PORT, flush=True)
    async with server:
        await server.serve_forever()


asyncio.run(main())
