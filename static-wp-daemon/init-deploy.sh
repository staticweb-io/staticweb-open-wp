#!/bin/bash
set -e

cd `dirname $(realpath $0)`
bb -m io.staticweb.static-wp-daemon.init-deploy
