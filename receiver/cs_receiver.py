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

from threading import Thread
from subprocess import Popen, PIPE, STDOUT
import select, socket
import SocketServer

HOST = ''
PORT = 53515
IP = '192.168.0.11'

bufferSize = 1024
meta_data = '{"port":%d,"name":"PyReceiver @ %s","id":"%s","width":1280,"height":960,"mirror":"h264","audio":"pcm","subtitles":"text/vtt","proxyHeaders":true,"hls":false,"upsell":true}' % (PORT, IP, IP)

SAVE_TO_FILE = False
class MyTCPHandler(SocketServer.BaseRequestHandler):
    def handle(self):
	if SAVE_TO_FILE:
            f = open('video.raw', 'wb')
        p = Popen(['ffplay', '-framerate', '30', '-'], stdin=PIPE, stdout=PIPE)
        #p = Popen(['gst-launch-1.0', 'fdsrc', '!', 'h264parse', '!', 'avdec_h264', '!', 'autovideosink'], stdin=PIPE, stdout=PIPE)
        skiped_metadata = False
        while True:
            data = self.request.recv(bufferSize)
            if data == None or len(data) <= 0:
                break
            if not skiped_metadata:
                print "Client connected, addr: ", self.client_address[0]
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

def resp_hello(ip, port):
    send_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    send_sock.sendto(meta_data, (ip, port))

def handle_discovery():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(('', PORT))
    s.setblocking(0)
    while True:
        result = select.select([s],[],[])
        if len(result[0]) <= 0:
            continue
        msg, address = result[0][0].recvfrom(bufferSize)
        print 'Receive broadcast msg: ', msg
        if msg == 'hello':
            print 'Got discover msg, src ip: %s, port: %d' % (address[0], address[1])
            resp_hello(address[0], address[1])


if __name__ == "__main__":
    server = SocketServer.TCPServer((HOST, PORT), MyTCPHandler)
    server_thread = Thread(target=server.serve_forever)
    server_thread.daemon = True
    server_thread.start()

    handle_discovery()
    server.shutdown()
