package org.apache.spark.sgx;

public class EnclaveCommunicator {
    private RingBuff encToOut;
    private RingBuff outToEnc;
    
	public EnclaveCommunicator(long encToOut, long outToEnc) {
    	this.encToOut = new RingBuff(encToOut, true);
    	this.outToEnc = new RingBuff(outToEnc, true);
	}

	public boolean writeToOutside(Object o) {
    	return this.encToOut.write(o);
	}
	
	public Object readFromOutside() {
    	return this.outToEnc.read();
	}
}