#!/usr/bin/env python3

import os
meta = open(os.environ["META"], "w")

while True:
    x = input("hello? ")
    meta.write(x + "\n")
    meta.flush()
