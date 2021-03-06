/*******************************************************************************
 *     Copyright (C) 2015 Jordan Dalton (jordan.8474@gmail.com)
 *
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License along
 *     with this program; if not, write to the Free Software Foundation, Inc.,
 *     51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *******************************************************************************/
package ovh.tgrhavoc.aibot.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.Security;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKey;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import ovh.tgrhavoc.aibot.Connection;
import ovh.tgrhavoc.aibot.MinecraftBot;
import ovh.tgrhavoc.aibot.ProxyData;
import ovh.tgrhavoc.aibot.event.EventBus;
import ovh.tgrhavoc.aibot.event.EventHandler;
import ovh.tgrhavoc.aibot.event.EventListener;
import ovh.tgrhavoc.aibot.event.general.DisconnectEvent;
import ovh.tgrhavoc.aibot.event.io.PacketProcessEvent;
import ovh.tgrhavoc.aibot.event.io.PacketReceivedEvent;
import ovh.tgrhavoc.aibot.event.io.PacketSentEvent;

public class SocketConnectionHandler<H extends PacketHeader> implements ConnectionHandler, EventListener {
	private final MinecraftBot bot;
	private final Protocol<H> protocol;
	private final Queue<ReadablePacket> packetProcessQueue;
	private final Queue<WriteablePacket> packetWriteQueue;
	private final Connection connection;

	private final AtomicBoolean pauseReading, pauseWriting;

	private ReadTask readTask;
	private WriteTask writeTask;

	private SecretKey sharedKey;
	private boolean encrypting, decrypting;

	public SocketConnectionHandler(MinecraftBot bot, Protocol<H> protocol, String server, int port) {
		this(bot, protocol, server, port, null);
	}

	public SocketConnectionHandler(MinecraftBot bot, Protocol<H> protocol, String server, int port, ProxyData socksProxy) {
		this.bot = bot;
		this.protocol = protocol;
		packetProcessQueue = new ArrayDeque<ReadablePacket>();
		packetWriteQueue = new ArrayDeque<WriteablePacket>();

		pauseReading = new AtomicBoolean();
		pauseWriting = new AtomicBoolean();

		if(socksProxy != null)
			connection = new Connection(server, port, socksProxy);
		else
			connection = new Connection(server, port);
		bot.getEventBus().register(this);
	}

	@EventHandler
	public void onDisconnect(DisconnectEvent event) {
		if(isConnected())
			connection.disconnect();
	}

	@Override
	public void sendPacket(WriteablePacket packet) {
		synchronized(packetWriteQueue) {
			packetWriteQueue.offer(packet);
			packetWriteQueue.notifyAll();
		}
	}

	@Override
	public synchronized void connect() throws IOException {
		if(connection.isConnected())
			return;
		connection.connect();

		ExecutorService service = bot.getService();
		readTask = new ReadTask();
		writeTask = new WriteTask();
		readTask.future = service.submit(readTask);
		writeTask.future = service.submit(writeTask);
	}

	@Override
	public synchronized void disconnect(String reason) {
		if(!connection.isConnected() && readTask.future == null && writeTask.future == null)
			return;
		if(readTask != null)
			readTask.future.cancel(true);
		if(writeTask != null)
			writeTask.future.cancel(true);
		readTask = null;
		writeTask = null;
		sharedKey = null;
		encrypting = decrypting = false;
		connection.disconnect();
		bot.getEventBus().fire(new DisconnectEvent(reason));
	}

	@Override
	public synchronized void process() {
		ReadablePacket[] packets;
		synchronized(packetProcessQueue) {
			if(packetProcessQueue.size() == 0)
				return;
			packets = packetProcessQueue.toArray(new ReadablePacket[packetProcessQueue.size()]);
			packetProcessQueue.clear();
		}
		EventBus eventBus = bot.getEventBus();
		for(ReadablePacket packet : packets)
			eventBus.fire(new PacketProcessEvent(packet));
	}

	@Override
	public Protocol<?> getProtocol() {
		return protocol;
	}

	@Override
	public boolean isConnected() {
		return connection.isConnected() && readTask != null && !readTask.future.isDone() && writeTask != null && !writeTask.future.isDone();
	}

	@Override
	public String getServer() {
		return connection.getHost();
	}

	@Override
	public int getPort() {
		return connection.getPort();
	}

	@Override
	public boolean supportsEncryption() {
		return true;
	}

	@Override
	public SecretKey getSharedKey() {
		return sharedKey;
	}

	@Override
	public void setSharedKey(SecretKey sharedKey) {
		if(this.sharedKey != null)
			throw new IllegalStateException("Shared key already set");
		this.sharedKey = sharedKey;
	}

	@Override
	public boolean isEncrypting() {
		return encrypting;
	}

	@Override
	public synchronized void enableEncryption() {
		if(!isConnected())
			throw new IllegalStateException("Not connected");
		if(encrypting)
			throw new IllegalStateException("Already encrypting");
		if(sharedKey == null)
			throw new IllegalStateException("Shared key not set");
		if(!pauseWriting.get() && (writeTask.thread == null || writeTask.thread != Thread.currentThread()))
			throw new IllegalStateException("Must be called from write thread");
		connection.setOutputStream(new DataOutputStream(EncryptionUtil.encryptOutputStream(connection.getOutputStream(), sharedKey)));
		encrypting = true;
	}

	@Override
	public boolean isDecrypting() {
		return decrypting;
	}

	@Override
	public synchronized void enableDecryption() {
		if(!isConnected())
			throw new IllegalStateException("Not connected");
		if(decrypting)
			throw new IllegalStateException("Already decrypting");
		if(sharedKey == null)
			throw new IllegalStateException("Shared key not set");
		if(!pauseReading.get() && (readTask.thread == null || readTask.thread != Thread.currentThread()))
			throw new IllegalStateException("Must be called from read thread");
		connection.setInputStream(new DataInputStream(EncryptionUtil.decryptInputStream(connection.getInputStream(), sharedKey)));
		decrypting = true;
	}

	@Override
	public boolean supportsPausing() {
		return true;
	}

	@Override
	public void pauseReading() {
		synchronized(pauseReading) {
			pauseReading.set(true);
			pauseReading.notifyAll();
		}
	}

	@Override
	public void pauseWriting() {
		synchronized(pauseWriting) {
			pauseWriting.set(true);
			pauseWriting.notifyAll();
		}
		synchronized(packetWriteQueue) {
			packetWriteQueue.notifyAll();
		}
	}

	@Override
	public void resumeReading() {
		synchronized(pauseReading) {
			pauseReading.set(false);
			pauseReading.notifyAll();
		}
	}

	@Override
	public void resumeWriting() {
		synchronized(pauseWriting) {
			pauseWriting.set(false);
			pauseWriting.notifyAll();
		}
		synchronized(packetWriteQueue) {
			packetWriteQueue.notifyAll();
		}
	}

	@Override
	public boolean isReadingPaused() {
		return pauseReading.get();
	}

	@Override
	public boolean isWritingPaused() {
		return pauseWriting.get();
	}

	private final class ReadTask implements Runnable {
		private Future<?> future;
		private Thread thread;

		@Override
		public void run() {
			thread = Thread.currentThread();
			try {
				Thread.sleep(500);
				while(isConnected()) {
					try {
						synchronized(pauseReading) {
							if(pauseReading.get()) {
								pauseReading.wait(500);
								continue;
							}
						}
					} catch(InterruptedException exception) {
						if(future == null || future.isCancelled())
							break;
						continue;
					}

					DataInputStream in = connection.getInputStream();
					final H header = protocol.readHeader(in);
					if(header == null)
						throw new IOException("Invalid header");
					ReadablePacket packet = (ReadablePacket) protocol.createPacket(header);
					if(packet == null || !(packet instanceof ReadablePacket))
						throw new IOException("Bad packet with header: " + header.toString());

					if(header instanceof PacketLengthHeader) {
						int length = ((PacketLengthHeader) header).getLength() - AbstractPacketX.varIntLength(header.getId());
						final byte[] data = new byte[length];
						in.readFully(data);

						in = new DataInputStream(new ByteArrayInputStream(data) {
							@Override
							public synchronized int read() {
								if(pos == count)
									System.out.println("WARNING: Packet 0x" + Integer.toHexString(header.getId()).toUpperCase() + " read past length of "
											+ data.length);
								return super.read();
							}

							@Override
							public void close() throws IOException {
								if(pos != count)
									System.out.println("WARNING: Packet 0x" + Integer.toHexString(header.getId()).toUpperCase() + " read less than "
											+ data.length + " (" + pos + ")");
							}
						});
					}
					packet.readData(in);

					if(header instanceof PacketLengthHeader)
						in.close();

					bot.getEventBus().fire(new PacketReceivedEvent(packet));
					synchronized(packetProcessQueue) {
						packetProcessQueue.offer(packet);
					}
				}
			} catch(Throwable exception) {
				exception.printStackTrace();
				disconnect("Read error: " + exception);
			}
		}
	}

	private final class WriteTask implements Runnable {
		private Future<?> future;
		private Thread thread;

		@Override
		public void run() {
			thread = Thread.currentThread();
			try {
				Thread.sleep(500);
				while(isConnected()) {
					WriteablePacket packet = null;
					try {
						synchronized(pauseWriting) {
							if(pauseWriting.get()) {
								pauseWriting.wait(500);
								continue;
							}
						}

						synchronized(packetWriteQueue) {
							if(!packetWriteQueue.isEmpty())
								packet = packetWriteQueue.poll();
							else
								packetWriteQueue.wait(500);
						}
					} catch(InterruptedException exception) {
						if(future == null || future.isCancelled())
							break;
						continue;
					}
					if(packet != null) {
						ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
						packet.writeData(new DataOutputStream(byteOutputStream));
						byte[] data = byteOutputStream.toByteArray();

						DataOutputStream out = connection.getOutputStream();
						PacketHeader header = protocol.createHeader(packet, data);

						header.write(out);
						out.write(data);
						out.flush();

						bot.getEventBus().fire(new PacketSentEvent(packet));
					}
				}
			} catch(Throwable exception) {
				exception.printStackTrace();
				disconnect("Write error: " + exception);
			}
		}
	}

	static {
		Security.addProvider(new BouncyCastleProvider());
	}
}
