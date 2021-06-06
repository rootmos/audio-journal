#!/usr/bin/env python3

import os
import time
import boto3

meta = open(os.environ["META"], "w")

if __name__ == "__main__":
    print("foo")
    meta.write("bar\n")
    meta.flush()
    time.sleep(2)
