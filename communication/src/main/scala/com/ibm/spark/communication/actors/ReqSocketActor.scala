/*
 * Copyright 2015 IBM Corp.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.ibm.spark.communication.actors

import akka.actor.{Actor, ActorRef}
import akka.util.ByteString
import com.ibm.spark.communication.{ZMQMessage, SocketManager}
import com.ibm.spark.utils.LogLike
import org.zeromq.ZMQ

/**
 * Represents an actor containing a request socket.
 *
 * @param connection The address to connect to
 * @param listener The actor to send incoming messages back to
 */
class ReqSocketActor(connection: String, listener: ActorRef)
  extends Actor with LogLike
{
  logger.debug(s"Initializing request socket actor for $connection")
  private val manager: SocketManager = new SocketManager
  private val socket = manager.newReqSocket(connection, (message: Seq[String]) => {
    listener ! ZMQMessage(message.map(ByteString.apply): _*)
  })

  override def postStop(): Unit = {
    manager.closeSocket(socket)
  }

  override def receive: Actor.Receive = {
    case zmqMessage: ZMQMessage =>
      val frames = zmqMessage.frames.map(byteString =>
        new String(byteString.toArray, ZMQ.CHARSET))
      socket.send(frames: _*)
  }
}
