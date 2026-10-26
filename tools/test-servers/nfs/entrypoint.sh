#!/bin/sh
# rpcbind, then unfsd registered with it: NFSv3 on 2049 and MOUNT on 892 (Synology's mountd port), TCP and UDP, so
# that a client asking the portmapper (111) finds both, as it does on a NAS. unfsd stays in the foreground and logs
# to stdout; stopping the container stops it and rpcbind with it.
set -eu

mkdir -p /run/rpcbind
rpcbind -f &
rpcbind_pid=$!

# rpcbind answers before unfsd registers.
tries=0
until rpcinfo -p 127.0.0.1 >/dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -le 50 ] || { echo "rpcbind did not start" >&2; exit 1; }
    sleep 0.1
done

trap 'kill "$rpcbind_pid" 2>/dev/null' EXIT INT TERM
unfsd -d -e /etc/exports -n 2049 -m 892 &
unfsd_pid=$!
wait "$unfsd_pid"
