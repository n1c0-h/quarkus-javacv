#!/bin/sh
# LocalStack ready hook (dev mode and tests): creates the bucket and uploads the fixed sounds.
set -e
awslocal s3 mb s3://audio
awslocal s3 cp --recursive --quiet /etc/localstack/init/ready.d/fixed-sounds s3://audio/fixed-sounds
echo "Fixed sounds seeded"
