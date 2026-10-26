#!/bin/sh
# rpcbind, then unfsd registered with it: MOUNT on 892 (Synology's mountd port), TCP and UDP, and NFSv3 over TCP on
# 2049, each reply in one fragment as a NAS sends it (whole-replies.py, before unfsd on 2050). With
# UNFS3_FRAGMENTS=1, as the nfs-fragments service runs, unfsd answers on 2049 itself, its replies above 64 KB in
# fragments, as NFS servers on libtirpc send them (README). The servers log to stdout.
set -eu

mkdir -p /run/rpcbind
rpcbind -f &

# until_registered PROGRAM PORT: waits for rpcbind, or for PROGRAM version 3 over TCP at PORT.
until_registered() {
    tries=0
    until rpcinfo -p 127.0.0.1 2>/dev/null | grep -Eq "^ +$1 +3 +tcp +$2( |\$)"; do
        tries=$((tries + 1))
        [ "$tries" -le 100 ] || { echo "Program $1 not registered at $2" >&2; exit 1; }
        sleep 0.1
    done
}
until_registered 100000 111

if [ "${UNFS3_FRAGMENTS:-0}" = 1 ]; then
    echo "NFS on port 2049 by unfsd itself, its replies above 64 KB in fragments"
    exec unfsd -d -e /etc/exports -n 2049 -m 892
fi

unfsd -d -e /etc/exports -n 2050 -m 892 &
until_registered 100003 2050
# NFS is reached at 2049 alone, where whole-replies.py registers it once listening.
rpcinfo -d 100003 3
exec python3 /usr/local/bin/whole-replies.py
