package org.apache.spark.sgx.shm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.spark.sgx.data.MappedDataBuffer;

/**
 * This class is to be used by the enclave to communicate with the outside.
 * 
 * @author Florian Kelbert
 *
 */
public final class ShmCommunicationManager<T> implements Callable<T> {
	
	private RingBuffConsumer reader;
	private RingBuffProducer writer;

	private final Object lockWriteBuff = new Object();
	private final Object lockReadBuff = new Object();
	private final Object lockInboxes = new Object();
	private final static Object lockInstance = new Object();

	private Map<Long, BlockingQueue<Object>> inboxes = new HashMap<>();
	private BlockingQueue<ShmCommunicator> accepted = new LinkedBlockingQueue<>();
	
	private long inboxCtr = 1;
	
	private static ShmCommunicationManager<?> _instance = null;
	

	private ShmCommunicationManager(String file, int size) {
		System.out.println("ShmCommunicationManager: " + file + " " + size);
		long[] handles = RingBuffLibWrapper.init_shm(file, size);
		System.out.println("ShmCommunicationManager: " + file + " " + size + " initialized: " + handles[0] + ", " + handles[1]);
		
		this.reader = new RingBuffConsumer(new MappedDataBuffer(handles[0], size));
		this.writer = new RingBuffProducer(new MappedDataBuffer(handles[1], size));
	}

	private ShmCommunicationManager(long writeBuff, long readBuff, int size) {
		this.reader = new RingBuffConsumer(new MappedDataBuffer(readBuff, size));
		this.writer = new RingBuffProducer(new MappedDataBuffer(writeBuff, size));
	}
	
	@SuppressWarnings("unchecked")
	public static <T> ShmCommunicationManager<T> create(String file, int size) {
		synchronized(lockInstance) {
			if (_instance == null) {
				_instance = new ShmCommunicationManager<T>(file, size);
			}
		}
		return (ShmCommunicationManager<T>) _instance;
	}	
	
	@SuppressWarnings("unchecked")
	public static <T> ShmCommunicationManager<T> create(long writeBuff, long readBuff, int size) {
		synchronized(lockInstance) {
			if (_instance == null) {
				_instance = new ShmCommunicationManager<T>(writeBuff, readBuff, size);
			}
		}
		return (ShmCommunicationManager<T>) _instance;
	}
	
	@SuppressWarnings("unchecked")
	public static <T> ShmCommunicationManager<T> get() {
		if (_instance == null) {
			throw new RuntimeException("ShmCommunicationManager was not instantiated.");
		}
		return (ShmCommunicationManager<T>) _instance;
	}

	public ShmCommunicator newShmCommunicator() {
		return newShmCommunicator(true);
	}

	public ShmCommunicator newShmCommunicator(boolean doConnect) {
		BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
		long myport;
		synchronized (lockInboxes) {
			myport = inboxCtr++;
			inboxes.put(myport, inbox);
		}

		return new ShmCommunicator(myport, inbox, doConnect);
	}

	/**
	 * Waits for a new incoming connections. This call blocks until
	 * a new connection is actually made. Once a connection is made,
	 * this method returns a {@link ShmCommunicator} object that
	 * represents this new connection. 
	 * This is conceptually similar to accept() on TCP sockets.
	 * 
	 * @return a {@link ShmCommunicator} representing the accepted connection
	 */
	public ShmCommunicator accept() {
		ShmCommunicator result = null;
		do {
			try {
				result = accepted.take();
			} catch (InterruptedException e) {
				result = null;
				e.printStackTrace();
			}
		} while (result == null);
		return result;
	}

	/**
	 * Writes to the outside.
	 * 
	 * @param o the object to write
	 * @return whether the write was successful
	 */
	void write(Object o, long theirPort) {
		write(new ShmMessage(EShmMessageType.REGULAR, o, theirPort));
	}

	void write(ShmMessage m) {
		synchronized (lockWriteBuff) {
			writer.write(m);
		}
	}
	
	void close(ShmCommunicator com) {
		inboxes.remove(com.getMyPort());
	}

	@Override
	public T call() throws Exception {
		ShmMessage msg = null;
		while (true) {
			synchronized (lockReadBuff) {
				msg = ((ShmMessage) reader.read());
			}

			if (msg.getPort() == 0) {
				switch (msg.getType()) {
				case NEW_CONNECTION:
					BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
					long myport;
					synchronized (lockInboxes) {
						myport = inboxCtr++;
						inboxes.put(myport, inbox);
					}
					accepted.put(new ShmCommunicator(myport, (long) msg.getMsg(), inbox));
					write(new ShmMessage(EShmMessageType.ACCEPTED_CONNECTION, myport, (long) msg.getMsg()));
					break;

				default:
					break;
				}
			} else {
				BlockingQueue<Object> inbox;
				synchronized (lockInboxes) {
					inbox = inboxes.get(msg.getPort());
				}
				inbox.add(msg.getMsg());
			}
		}
	}
	
	@Override
	public String toString() {
		return this.getClass().getSimpleName();
	}
}