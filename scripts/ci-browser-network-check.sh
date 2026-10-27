#!/usr/bin/env bash
# #696: verify the disposable CI runner's new-interface IPv6 setting before Chromium starts.
# The caller sets net.ipv6.conf.default.disable_ipv6=1; existing interfaces are not retuned.
# A real veth pair must inherit the setting and stay without IPv6 addresses when brought up.
# Run as root on the CI runner, or inside an isolated network namespace with CAP_NET_ADMIN.
set -euo pipefail

default_disabled=$(sysctl -n net.ipv6.conf.default.disable_ipv6)
if [[ "$default_disabled" != 1 ]]; then
  echo 'ci-browser-network-check: FAIL: new interfaces must inherit disable_ipv6=1' >&2
  exit 1
fi

probe="dpc${BASHPID}a"
peer="dpc${BASHPID}b"
created=false
cleanup() {
  if [[ "$created" == true ]]; then
    ip link delete "$probe"
  fi
}
trap cleanup EXIT

ip link add "$probe" type veth peer name "$peer"
created=true
for interface in "$probe" "$peer"; do
  disabled=$(cat "/proc/sys/net/ipv6/conf/$interface/disable_ipv6")
  if [[ "$disabled" != 1 ]]; then
    echo "ci-browser-network-check: FAIL: $interface did not inherit disable_ipv6=1" >&2
    exit 1
  fi
  ip link set "$interface" up
done

for interface in "$probe" "$peer"; do
  addresses=$(ip -6 -o address show dev "$interface")
  if [[ -n "$addresses" ]]; then
    echo "ci-browser-network-check: FAIL: $interface acquired an IPv6 address" >&2
    exit 1
  fi
done
cleanup
created=false
trap - EXIT
echo 'ci-browser-network-check: PASS: fresh veth peers inherit disable_ipv6=1 and have no IPv6 addresses'
