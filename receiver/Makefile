all: cs_receiver cs_receiver_conn cs_receiver_arm cs_receiver_conn_arm

cs_receiver: cs_receiver.c
	gcc -o $@ $< $(LIBS)

cs_receiver_conn: cs_receiver.c
	gcc -DCLIENT_MODE -o $@ $< $(LIBS)

cs_receiver_arm: cs_receiver.c
	arm-cortexa9_neon-linux-gnueabi-gcc -DVPUDEC -o $@ $< $(LIBS)

cs_receiver_conn_arm: cs_receiver.c
	arm-cortexa9_neon-linux-gnueabi-gcc -DCLIENT_MODE -DVPUDEC -o $@ $< $(LIBS)

clean:
	rm -f cs_receiver
	rm -f cs_receiver_conn
	rm -f cs_receiver_arm
	rm -f cs_receiver_conn_arm
