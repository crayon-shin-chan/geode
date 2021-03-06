/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.apache.geode.redis.internal.netty;


import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import org.apache.logging.log4j.Logger;

import org.apache.geode.cache.CacheClosedException;
import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.cache.execute.FunctionInvocationTargetException;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.redis.internal.GeodeRedisServer;
import org.apache.geode.redis.internal.ParameterRequirements.RedisParametersMismatchException;
import org.apache.geode.redis.internal.RedisCommandType;
import org.apache.geode.redis.internal.RedisConstants;
import org.apache.geode.redis.internal.RedisStats;
import org.apache.geode.redis.internal.RegionProvider;
import org.apache.geode.redis.internal.data.RedisDataTypeMismatchException;
import org.apache.geode.redis.internal.executor.CommandFunction;
import org.apache.geode.redis.internal.executor.RedisResponse;
import org.apache.geode.redis.internal.pubsub.PubSub;

/**
 * This class extends {@link ChannelInboundHandlerAdapter} from Netty and it is the last part of the
 * channel pipeline. The {@link ByteToCommandDecoder} forwards a {@link Command} to this class which
 * executes it and sends the result back to the client. Additionally, all exception handling is done
 * by this class.
 * <p>
 * Besides being part of Netty's pipeline, this class also serves as a context to the execution of a
 * command. It provides access to the {@link RegionProvider} and anything else an executing {@link
 * Command} may need.
 */
public class ExecutionHandlerContext extends ChannelInboundHandlerAdapter {

  private static final Logger logger = LogService.getLogger();

  private final Client client;
  private final Channel channel;
  private final RegionProvider regionProvider;
  private final PubSub pubsub;
  private final ByteBufAllocator byteBufAllocator;
  private final byte[] authPassword;
  private final Supplier<Boolean> allowUnsupportedSupplier;
  private final Runnable shutdownInvoker;
  private final RedisStats redisStats;
  private final ExecutorService backgroundExecutor;
  private final LinkedBlockingQueue<Command> commandQueue = new LinkedBlockingQueue<>();

  private boolean isAuthenticated;

  /**
   * Default constructor for execution contexts.
   *
   * @param channel Channel used by this context, should be one to one
   * @param password Authentication password for each context, can be null
   */
  public ExecutionHandlerContext(Channel channel, RegionProvider regionProvider, PubSub pubsub,
      Supplier<Boolean> allowUnsupportedSupplier,
      Runnable shutdownInvoker,
      RedisStats redisStats,
      ExecutorService backgroundExecutor,
      byte[] password) {
    this.channel = channel;
    this.regionProvider = regionProvider;
    this.pubsub = pubsub;
    this.allowUnsupportedSupplier = allowUnsupportedSupplier;
    this.shutdownInvoker = shutdownInvoker;
    this.redisStats = redisStats;
    this.backgroundExecutor = backgroundExecutor;
    this.client = new Client(channel);
    this.byteBufAllocator = this.channel.alloc();
    this.authPassword = password;
    this.isAuthenticated = password == null;
    redisStats.addClient();
  }

  public ChannelFuture writeToChannel(RedisResponse response) {
    return channel.writeAndFlush(response.encode(byteBufAllocator), channel.newPromise());
  }

  /**
   * This will handle the execution of received commands
   */
  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    Command command = (Command) msg;
    command.setChannelHandlerContext(ctx);
    synchronized (commandQueue) {
      if (!commandQueue.isEmpty()) {
        commandQueue.offer(command);
        return;
      }
      if (command.getCommandType().isAsync()) {
        commandQueue.offer(command);
        startAsyncCommandExecution(command);
        return;
      }
      executeCommand(command);
    }
  }

  /**
   * Exception handler for the entire pipeline
   */
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    if (cause instanceof IOException) {
      channelInactive(ctx);
      return;
    }
    writeToChannel(getExceptionResponse(ctx, cause));
  }

  private RedisResponse getExceptionResponse(ChannelHandlerContext ctx, Throwable cause) {
    RedisResponse response;

    if (cause instanceof FunctionException
        && !(cause instanceof FunctionInvocationTargetException)) {
      Throwable th = CommandFunction.getInitialCause((FunctionException) cause);
      if (th != null) {
        cause = th;
      }
    }

    if (cause instanceof NumberFormatException) {
      response = RedisResponse.error(cause.getMessage());
    } else if (cause instanceof ArithmeticException) {
      response = RedisResponse.error(cause.getMessage());
    } else if (cause instanceof RedisDataTypeMismatchException) {
      response = RedisResponse.wrongType(cause.getMessage());
    } else if (cause instanceof DecoderException
        && cause.getCause() instanceof RedisCommandParserException) {
      response = RedisResponse.error(RedisConstants.PARSING_EXCEPTION_MESSAGE);

    } else if (cause instanceof InterruptedException || cause instanceof CacheClosedException) {
      response = RedisResponse.error(RedisConstants.SERVER_ERROR_SHUTDOWN);
    } else if (cause instanceof IllegalStateException
        || cause instanceof RedisParametersMismatchException) {
      response = RedisResponse.error(cause.getMessage());
    } else if (cause instanceof FunctionInvocationTargetException) {
      // This indicates a member departed
      String errorMsg = cause.getMessage();
      if (!errorMsg.contains("memberDeparted")) {
        errorMsg = "memberDeparted: " + errorMsg;
      }
      response = RedisResponse.error(errorMsg);
    } else {
      if (logger.isErrorEnabled()) {
        logger.error("GeodeRedisServer-Unexpected error handler for " + ctx.channel(), cause);
      }
      response = RedisResponse.error(RedisConstants.SERVER_ERROR_MESSAGE);
    }

    return response;
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    if (logger.isDebugEnabled()) {
      logger.debug("GeodeRedisServer-Connection closing with " + ctx.channel().remoteAddress());
    }
    redisStats.removeClient();
    ctx.channel().close();
    ctx.close();
  }

  private void startAsyncCommandExecution(Command command) {
    if (logger.isDebugEnabled()) {
      logger.debug("Starting execution of async Redis command: {}", command);
    }
    final long start = redisStats.startCommand(command.getCommandType());
    command.setAsyncStartTime(start);
    command.execute(this);
  }

  public void endAsyncCommandExecution(Command command, RedisResponse response) {
    synchronized (commandQueue) {
      Command head = takeFromCommandQueue();
      if (head != command) {
        throw new IllegalStateException(
            "expected " + command + " but found " + head + " in the queue");
      }
      try {
        writeToChannel(response);
      } finally {
        redisStats.endCommand(command.getCommandType(), command.getAsyncStartTime());
      }
      drainCommandQueue();
    }
  }

  public void endAsyncCommandExecution(Command command, Throwable exception) {
    synchronized (commandQueue) {
      Command head = takeFromCommandQueue();
      if (head != command) {
        throw new IllegalStateException(
            "expected " + command + " but found " + head + " in the queue");
      }
      try {
        exceptionCaught(command.getChannelHandlerContext(), exception);
      } finally {
        redisStats.endCommand(command.getCommandType(), command.getAsyncStartTime());
      }
      drainCommandQueue();
    }
  }

  private Command takeFromCommandQueue() {
    try {
      return commandQueue.take();
    } catch (InterruptedException e) {
      Thread.interrupted();
      throw new IllegalStateException("unexpected interrupt");
    }
  }

  /**
   * execute all commands in the queue until an async one is found.
   * If an async one is found start it.
   */
  private void drainCommandQueue() {
    Command command;
    while ((command = commandQueue.peek()) != null) {
      if (command.getCommandType().isAsync()) {
        startAsyncCommandExecution(command);
        return;
      } else {
        takeFromCommandQueue();
        try {
          executeCommand(command);
        } catch (Throwable ex) {
          exceptionCaught(command.getChannelHandlerContext(), ex);
        }
      }
    }
  }

  private void executeCommand(Command command) {
    RedisResponse response;
    try {
      if (logger.isDebugEnabled()) {
        logger.debug("Executing Redis command: {}", command);
      }

      if (!isAuthenticated()) {
        response = handleUnAuthenticatedCommand(command);
        writeToChannel(response);
        return;
      }

      if (command.isUnsupported() && !allowUnsupportedCommands()) {
        writeToChannel(
            RedisResponse
                .error(command.getCommandType() + RedisConstants.ERROR_UNSUPPORTED_COMMAND));
        return;
      }

      if (command.isUnimplemented()) {
        logger.info("Failed " + command.getCommandType() + " because it is not implemented.");
        writeToChannel(RedisResponse.error(command.getCommandType() + " is not implemented."));
        return;
      }

      final long start = redisStats.startCommand(command.getCommandType());
      try {
        response = command.execute(this);
        if (response == null) {
          return;
        }
        logResponse(response);
        writeToChannel(response);
      } finally {
        redisStats.endCommand(command.getCommandType(), start);
      }

      if (command.isOfType(RedisCommandType.QUIT)) {
        channelInactive(command.getChannelHandlerContext());
      }
    } catch (Exception e) {
      logger.warn("Execution of Redis command {} failed: {}", command, e);
      throw e;
    }
  }

  private boolean allowUnsupportedCommands() {
    return allowUnsupportedSupplier.get();
  }


  private RedisResponse handleUnAuthenticatedCommand(Command command) {
    RedisResponse response;
    if (command.isOfType(RedisCommandType.AUTH)) {
      response = command.execute(this);
    } else {
      response = RedisResponse.customError(RedisConstants.ERROR_NOT_AUTH);
    }
    return response;
  }

  private void logResponse(RedisResponse response) {
    if (logger.isDebugEnabled() && response != null) {
      ByteBuf buf = response.encode(new UnpooledByteBufAllocator(false));
      logger.debug("Redis command returned: {}",
          Command.getHexEncodedString(buf.array(), buf.readableBytes()));
    }
  }

  /**
   * {@link ByteBuf} allocator for this context. All executors must use this pooled allocator as
   * opposed to having unpooled buffers for maximum performance
   *
   * @return allocator instance
   */
  public ByteBufAllocator getByteBufAllocator() {
    return this.byteBufAllocator;
  }

  /**
   * Gets the provider of Regions
   */
  public RegionProvider getRegionProvider() {
    return regionProvider;
  }

  /**
   * Get the channel for this context
   *
   *
   * public Channel getChannel() { return this.channel; }
   */

  /**
   * Get the authentication password, this will be same server wide. It is exposed here as opposed
   * to {@link GeodeRedisServer}.
   */
  public byte[] getAuthPassword() {
    return this.authPassword;
  }

  /**
   * Checker if user has authenticated themselves
   *
   * @return True if no authentication required or authentication complete, false otherwise
   */
  public boolean isAuthenticated() {
    return this.isAuthenticated;
  }

  /**
   * Lets this context know the authentication is complete
   */
  public void setAuthenticationVerified() {
    this.isAuthenticated = true;
  }

  public Client getClient() {
    return client;
  }

  public void shutdown() {
    shutdownInvoker.run();
  }

  public PubSub getPubSub() {
    return pubsub;
  }

  public ExecutorService getBackgroundExecutor() {
    return backgroundExecutor;
  }
}
