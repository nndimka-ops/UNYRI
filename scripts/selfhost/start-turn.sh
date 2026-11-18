#!/bin/sh
set -eu
umask 077
# Pass the secret through a private tmpfs file, not through the process command line.
cat > /tmp/turnserver.conf <<EOF
listening-port=3478
min-port=49152
max-port=49200
realm=$UNYRI_DOMAIN
external-ip=$TURN_EXTERNAL_IP
use-auth-secret
static-auth-secret=$TURN_SHARED_SECRET
no-tls
no-dtls
no-cli
no-multicast-peers
no-tcp-relay
user-quota=12
total-quota=1200
max-bps=3000000
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.0.0.0-192.0.0.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=198.18.0.0-198.19.255.255
denied-peer-ip=224.0.0.0-255.255.255.255
denied-peer-ip=::1
denied-peer-ip=::ffff:0.0.0.0-::ffff:255.255.255.255
denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
log-file=stdout
simple-log
EOF
exec turnserver -c /tmp/turnserver.conf
