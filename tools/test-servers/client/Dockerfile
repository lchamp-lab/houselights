# Client tools to look at the test servers from the compose network, without mounting anything: smbclient, showmount,
# rpcinfo, and libnfs's nfs-ls and nfs-cat, which read an export in user space. Test-only.
FROM alpine:3.24.3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a

RUN apk add --no-cache "samba-client~4.23" "nfs-utils~2.6" "rpcbind~1.2" "libnfs-tools~5.0"
