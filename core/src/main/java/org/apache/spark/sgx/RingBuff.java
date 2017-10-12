package org.apache.spark.sgx;

import java.io.IOException;

class RingBuff {
        private long handle;
        private boolean blocking;

        RingBuff(long handle, boolean blocking) {
        	this.handle = handle;
        	this.blocking = blocking;
        }
        
        boolean write(Object o) {
        	boolean success = false;
        	boolean exception = false;
        	
        	do {
				try {
					success = RingBuffLibWrapper.write_msg(handle, Serialization.serialize(o));
				} catch (IOException e) {
					e.printStackTrace();
					exception = true;
				}
        	}
        	while (!success && !exception && blocking);
        	
			return success;
        }

        Object read() {
        	Object obj = null;
        	try {
				obj = Serialization.deserialize(RingBuffLibWrapper.read_msg(handle));
			} catch (ClassNotFoundException | IOException e) {
				e.printStackTrace();
			}
        	return obj;
        }
}
