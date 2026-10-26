# A Synology- or QNAP-like SMB server: Samba on Alpine, configured as smb.conf says. Test-only.
FROM alpine:3.24.3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a

# Samba of Alpine 3.24's branch (4.23); its fixes come with the branch, so the series is pinned, not the build.
RUN apk add --no-cache "samba-server~4.23" "samba-common-tools~4.23" \
    && smbd --version

# The read-only test account, without a shell or a home: houselights / houselights-test (README).
RUN adduser -D -H -s /sbin/nologin houselights \
    && printf 'houselights-test\nhouselights-test\n' | smbpasswd -a -s houselights

COPY smb.conf /etc/samba/smb.conf
RUN testparm -s >/dev/null

EXPOSE 445
HEALTHCHECK --interval=2s --timeout=2s --retries=30 CMD nc -z 127.0.0.1 445 || exit 1
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
