#!/bin/sh

GP_GIT_COMMIT=$(git rev-list --max-count=1 HEAD) || exit 1
SIMPLYE_VERSION=$(grep versionCode simplified-app-openebooks/version.properties | sed 's/versionCode/openebooks.versionCode/g') || exit 1

cat <<EOF
git.commit=${GP_GIT_COMMIT}
${SIMPLYE_VERSION}
EOF
