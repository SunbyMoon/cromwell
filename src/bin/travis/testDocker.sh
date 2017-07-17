#!/usr/bin/env bash

set -e

if [ "$TRAVIS_SECURE_ENV_VARS" = "false" ]; then
    echo "************************************************************************************************"
    echo "************************************************************************************************"
    echo "**                                                                                            **"
    echo "**  WARNING: Encrypted keys are unavailable to automatically test JES with centaur. Exiting.  **"
    echo "**                                                                                            **"
    echo "************************************************************************************************"
    echo "************************************************************************************************"
    exit 0
fi

docker login -u="$DOCKER_USERNAME" -p="$DOCKER_PASSWORD"
docker run --rm \
    -v $HOME:/root:rw \
    broadinstitute/dsde-toolbox vault auth -method=github token=FAKETOKEN
