#!/usr/bin/env bash
# 冒烟验收入口（参数透传给 python 实现）
exec python "$(dirname "$0")/smoke_test.py" "$@"
