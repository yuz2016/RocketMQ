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

import com.alibaba.rocketmq.remoting.protocol.RemotingCommand;

import io.netty.channel.ChannelHandlerContext;

/**
 * Common remoting command processor
 *
 * @author shijia.wxr
 *
 */

/**
 * <pre>
 * 请求处理器.
 * 用于RPC数据被decode并在{@link com.alibaba.rocketmq.remoting.RPCHook#doBeforeRequest(String, RemotingCommand)}方法调用后执行
 * </pre>
 * 
 * @author lvchenggang
 *
 */
public interface NettyRequestProcessor {
	RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request) throws Exception;

	boolean rejectRequest();
}
