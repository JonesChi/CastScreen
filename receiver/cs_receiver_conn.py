#!/usr/bin/env python

"""
 /*
 * Copyright (C) 2016 Jones Chi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""

from subprocess import Popen, PIPE, STDOUT
import socket

PORT = 53516
bufferSize = 1024

SAVE_TO_FILE = False
def connect_to_server():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_address = ('localhost', PORT)
    print 'Connecting to %s port %s' % server_address
    sock.connect(server_address)
    try:
        # Send data
        message = 'mirror\n'
        print 'Sending mirror cmd'
        sock.sendall(message)

	if SAVE_TO_FILE:
            f = open('video_client.raw', 'wb')
        p = Popen(['ffplay', '-framerate', '30', '-'], stdin=PIPE, stdout=PIPE)
        #p = Popen(['gst-launch-1.0', 'fdsrc', '!', 'h264parse', '!', 'avdec_h264', '!', 'autovideosink'], stdin=PIPE, stdout=PIPE)
        skiped_metadata = False
        while True:
            data = sock.recv(bufferSize)
            if data == None or len(data) <= 0:
                break
            if not skiped_metadata:
                if data.find('\r\n\r\n') > 0:
                    last_ctrl = data.find('\r\n\r\n') + 4
                    print 'Recv control data: ', data[0:last_ctrl]
                    if len(data) > last_ctrl:
                        p.stdin.write(data[last_ctrl:])
	                if SAVE_TO_FILE:
                            f.write(data[last_ctrl:])
                skiped_metadata = True
            else:
                p.stdin.write(data)
	        if SAVE_TO_FILE:
                    f.write(data)
        p.kill()
	if SAVE_TO_FILE:
            f.close()

    finally:
        sock.close()

if __name__ == "__main__":
    connect_to_server()
