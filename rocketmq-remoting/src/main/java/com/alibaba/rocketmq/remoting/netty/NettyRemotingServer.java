/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.alibaba.rocketmq.remoting.netty;

import java.net.InetSocketAddress;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.remoting.ChannelEventListener;
import com.alibaba.rocketmq.remoting.InvokeCallback;
import com.alibaba.rocketmq.remoting.RPCHook;
import com.alibaba.rocketmq.remoting.RemotingServer;
import com.alibaba.rocketmq.remoting.common.Pair;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import com.alibaba.rocketmq.remoting.common.RemotingUtil;
import com.alibaba.rocketmq.remoting.exception.RemotingSendRequestException;
import com.alibaba.rocketmq.remoting.exception.RemotingTimeoutException;
import com.alibaba.rocketmq.remoting.exception.RemotingTooMuchRequestException;
import com.alibaba.rocketmq.remoting.protocol.RemotingCommand;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;

/**

 *
 * @author shijia.wxr
 *
 */

/**
 * Netty Server
 * 
 * @author lvchenggang
 *
 */
public class NettyRemotingServer extends NettyRemotingAbstract implements RemotingServer {
	private static final Logger log = LoggerFactory.getLogger(RemotingHelper.RemotingLogName);
	private final ServerBootstrap serverBootstrap;
	private final EventLoopGroup eventLoopGroupSelector;
	private final EventLoopGroup eventLoopGroupBoss;
	private final NettyServerConfig nettyServerConfig;

	private final ExecutorService publicExecutor;
	private final ChannelEventListener channelEventListener;

	private final Timer timer = new Timer("ServerHouseKeepingService", true);
	private DefaultEventExecutorGroup defaultEventExecutorGroup;

	private RPCHook rpcHook;

	private int port = 0;

	public NettyRemotingServer(final NettyServerConfig nettyServerConfig) {
		this(nettyServerConfig, null);
	}

	public NettyRemotingServer(final NettyServerConfig nettyServerConfig,
			final ChannelEventListener channelEventListener) {
		super(nettyServerConfig.getServerOnewaySemaphoreValue(), nettyServerConfig.getServerAsyncSemaphoreValue());
		this.serverBootstrap = new ServerBootstrap();
		this.nettyServerConfig = nettyServerConfig;
		this.channelEventListener = channelEventListener;

		int publicThreadNums = nettyServerConfig.getServerCallbackExecutorThreads();
		if (publicThreadNums <= 0) {
			publicThreadNums = 4;
		}

		this.publicExecutor = Executors.newFixedThreadPool(publicThreadNums, new ThreadFactory() {
			private AtomicInteger threadIndex = new AtomicInteger(0);

			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "NettyServerPublicExecutor_" + this.threadIndex.incrementAndGet());
			}
		});

		this.eventLoopGroupBoss = new NioEventLoopGroup(1, new ThreadFactory() {
			private AtomicInteger threadIndex = new AtomicInteger(0);

			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, String.format("NettyBoss_%d", this.threadIndex.incrementAndGet()));
			}
		});

		if (RemotingUtil.isLinuxPlatform() //
				&& nettyServerConfig.isUseEpollNativeSelector()) {
			this.eventLoopGroupSelector = new EpollEventLoopGroup(nettyServerConfig.getServerSelectorThreads(),
					new ThreadFactory() {
						private AtomicInteger threadIndex = new AtomicInteger(0);
						private int threadTotal = nettyServerConfig.getServerSelectorThreads();

						@Override
						public Thread newThread(Runnable r) {
							return new Thread(r, String.format("NettyServerEPOLLSelector_%d_%d", threadTotal,
									this.threadIndex.incrementAndGet()));
						}
					});
		} else {
			this.eventLoopGroupSelector = new NioEventLoopGroup(nettyServerConfig.getServerSelectorThreads(),
					new ThreadFactory() {
						private AtomicInteger threadIndex = new AtomicInteger(0);
						private int threadTotal = nettyServerConfig.getServerSelectorThreads();

						@Override
						public Thread newThread(Runnable r) {
							return new Thread(r, String.format("NettyServerNIOSelector_%d_%d", threadTotal,
									this.threadIndex.incrementAndGet()));
						}
					});
		}
	}

	@Override
	public void start() {
		this.defaultEventExecutorGroup = new DefaultEventExecutorGroup(//
				nettyServerConfig.getServerWorkerThreads(), //
				new ThreadFactory() {

					private AtomicInteger threadIndex = new AtomicInteger(0);

					@Override
					public Thread newThread(Runnable r) {
						return new Thread(r, "NettyServerCodecThread_" + this.threadIndex.incrementAndGet());
					}
				});

		ServerBootstrap childHandler = //
				this.serverBootstrap.group(this.eventLoopGroupBoss, this.eventLoopGroupSelector)
						.channel(NioServerSocketChannel.class)
						/**
						 * <pre>
						 *  
						 * BACKLOG用于构造服务端套接字ServerSocket对象，标识当服务器请求处理线程全满时， 
						 * 用于临时存放已完成三次握手的请求的队列的最大长度
						 * </pre>
						 */
						.option(ChannelOption.SO_BACKLOG, 1024)
						/**
						 * <pre>
						 * 让端口释放后立即就可以被再次使用. 
						 * 这个主要用在server主动关闭的情况下,如果不启用这个参数, 会存在server在TIME_WAIT(等待client关闭连接)时间内不能再次绑定此端口
						 * 
						 * </pre>
						 */
						.option(ChannelOption.SO_REUSEADDR, true)
						/**
						 * <pre>
						 * 是否启用心跳保活机制。
						 * 在双方TCP套接字建立连接后（即都进入ESTABLISHED状态）并且在两个小时左右上层没有任何数据传输的情况下，这套机制才会被激活。
						 * 由于有专门的心跳检测, 则不需要这个配置选项了
						 * </pre>
						 */
						.option(ChannelOption.SO_KEEPALIVE, false)
						/**
						 * <pre>
						 * 关闭Nagle算法, 这样有数据就立即传送,实时性高.
						 * 默认为false, 表示启用Nagle算法,实时性不高, 但可节约网络带宽
						 * </pre>
						 */
						.childOption(ChannelOption.TCP_NODELAY, true)
						/**
						 * 发送缓冲区
						 */
						.option(ChannelOption.SO_SNDBUF, nettyServerConfig.getServerSocketSndBufSize())
						/**
						 * 接收缓冲区
						 */
						.option(ChannelOption.SO_RCVBUF, nettyServerConfig.getServerSocketRcvBufSize())
						//
						.localAddress(new InetSocketAddress(this.nettyServerConfig.getListenPort()))
						.childHandler(new ChannelInitializer<SocketChannel>() {
							@Override
							public void initChannel(SocketChannel ch) throws Exception {
								ch.pipeline().addLast(
										//
										defaultEventExecutorGroup, //
										new NettyEncoder(), //
										new NettyDecoder(), //
										new IdleStateHandler(0, 0,
												nettyServerConfig.getServerChannelMaxIdleTimeSeconds()), //
										new NettyConnetManageHandler(), //
										new NettyServerHandler());
							}
						});

		if (nettyServerConfig.isServerPooledByteBufAllocatorEnable()) {
			childHandler.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
		}

		try {
			ChannelFuture sync = this.serverBootstrap.bind().sync();
			InetSocketAddress addr = (InetSocketAddress) sync.channel().localAddress();
			this.port = addr.getPort();
		} catch (InterruptedException e1) {
			throw new RuntimeException("this.serverBootstrap.bind().sync() InterruptedException", e1);
		}

		if (this.channelEventListener != null) {
			this.nettyEventExecuter.start();
		}

		this.timer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				try {
					NettyRemotingServer.this.scanResponseTable();
				} catch (Exception e) {
					log.error("scanResponseTable exception", e);
				}
			}
		}, 1000 * 3, 1000);
	}

	@Override
	public void shutdown() {
		try {
			if (this.timer != null) {
				this.timer.cancel();
			}

			this.eventLoopGroupBoss.shutdownGracefully();

			this.eventLoopGroupSelector.shutdownGracefully();

			if (this.nettyEventExecuter != null) {
				this.nettyEventExecuter.shutdown();
			}

			if (this.defaultEventExecutorGroup != null) {
				this.defaultEventExecutorGroup.shutdownGracefully();
			}
		} catch (Exception e) {
			log.error("NettyRemotingServer shutdown exception, ", e);
		}

		if (this.publicExecutor != null) {
			try {
				this.publicExecutor.shutdown();
			} catch (Exception e) {
				log.error("NettyRemotingServer shutdown exception, ", e);
			}
		}
	}

	@Override
	public void registerRPCHook(RPCHook rpcHook) {
		this.rpcHook = rpcHook;
	}

	@Override
	public void registerProcessor(int requestCode, NettyRequestProcessor processor, ExecutorService executor) {
		ExecutorService executorThis = executor;
		if (null == executor) {
			executorThis = this.publicExecutor;
		}

		Pair<NettyRequestProcessor, ExecutorService> pair = new Pair<NettyRequestProcessor, ExecutorService>(processor,
				executorThis);
		this.processorTable.put(requestCode, pair);
	}

	@Override
	public void registerDefaultProcessor(NettyRequestProcessor processor, ExecutorService executor) {
		this.defaultRequestProcessor = new Pair<NettyRequestProcessor, ExecutorService>(processor, executor);
	}

	@Override
	public int localListenPort() {
		return this.port;
	}

	@Override
	public Pair<NettyRequestProcessor, ExecutorService> getProcessorPair(int requestCode) {
		return processorTable.get(requestCode);
	}

	@Override
	public RemotingCommand invokeSync(final Channel channel, final RemotingCommand request, final long timeoutMillis)
			throws InterruptedException, RemotingSendRequestException, RemotingTimeoutException {
		return this.invokeSyncImpl(channel, request, timeoutMillis);
	}

	@Override
	public void invokeAsync(Channel channel, RemotingCommand request, long timeoutMillis, InvokeCallback invokeCallback)
			throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException,
			RemotingSendRequestException {
		this.invokeAsyncImpl(channel, request, timeoutMillis, invokeCallback);
	}

	@Override
	public void invokeOneway(Channel channel, RemotingCommand request, long timeoutMillis) throws InterruptedException,
			RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
		this.invokeOnewayImpl(channel, request, timeoutMillis);
	}

	@Override
	public ChannelEventListener getChannelEventListener() {
		return channelEventListener;
	}

	@Override
	public RPCHook getRPCHook() {
		return this.rpcHook;
	}

	@Override
	public ExecutorService getCallbackExecutor() {
		return this.publicExecutor;
	}

	class NettyServerHandler extends SimpleChannelInboundHandler<RemotingCommand> {

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, RemotingCommand msg) throws Exception {
			processMessageReceived(ctx, msg);
		}
	}

	/**
	 * <pre>
	 * Netty Server心跳检测ChannelHandler,配合IdleStateHandler使用
	 * 用于发送自定义的事件NettyEvent
	 * </pre>
	 * 
	 * @author lvchenggang
	 *
	 */
	class NettyConnetManageHandler extends ChannelDuplexHandler {
		@Override
		public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
			final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
			log.info("NETTY SERVER PIPELINE: channelRegistered {}", remoteAddress);
			super.channelRegistered(ctx);
		}

		@Override
		public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
			final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
			log.info("NETTY SERVER PIPELINE: channelUnregistered, the channel[{}]", remoteAddress);
			super.channelUnregistered(ctx);
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
			log.info("NETTY SERVER PIPELINE: channelActive, the channel[{}]", remoteAddress);
			super.channelActive(ctx);

			if (NettyRemotingServer.this.channelEventListener != null) {
				NettyRemotingServer.this
						.putNettyEvent(new NettyEvent(NettyEventType.CONNECT, remoteAddress.toString(), ctx.channel()));
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
			log.info("NETTY SERVER PIPELINE: channelInactive, the channel[{}]", remoteAddress);
			super.channelInactive(ctx);

			if (NettyRemotingServer.this.channelEventListener != null) {
				NettyRemotingServer.this
						.putNettyEvent(new NettyEvent(NettyEventType.CLOSE, remoteAddress.toString(), ctx.channel()));
			}
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if (evt instanceof IdleStateEvent) {
				IdleStateEvent evnet = (IdleStateEvent) evt;
				if (evnet.state().equals(IdleState.ALL_IDLE)) {
					final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
					log.warn("NETTY SERVER PIPELINE: IDLE exception [{}]", remoteAddress);
					RemotingUtil.closeChannel(ctx.channel());
					if (NettyRemotingServer.this.channelEventListener != null) {
						NettyRemotingServer.this.putNettyEvent(
								new NettyEvent(NettyEventType.IDLE, remoteAddress.toString(), ctx.channel()));
					}
				}
			}

			ctx.fireUserEventTriggered(evt);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
			final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
			log.warn("NETTY SERVER PIPELINE: exceptionCaught {}", remoteAddress);
			log.warn("NETTY SERVER PIPELINE: exceptionCaught exception.", cause);

			if (NettyRemotingServer.this.channelEventListener != null) {
				NettyRemotingServer.this.putNettyEvent(
						new NettyEvent(NettyEventType.EXCEPTION, remoteAddress.toString(), ctx.channel()));
			}

			RemotingUtil.closeChannel(ctx.channel());
		}
	}
}
