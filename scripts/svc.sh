#!/usr/bin/env bash
# 兼容别名：单独控制某个服务
exec "$(dirname "$0")/dev-up.sh" "$@"
