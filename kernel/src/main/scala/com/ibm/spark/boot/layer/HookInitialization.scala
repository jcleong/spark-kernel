/*
 * Copyright 2014 IBM Corp.
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

package com.ibm.spark.boot.layer

import com.ibm.spark.boot.KernelBootstrap
import com.ibm.spark.interpreter.Interpreter
import com.ibm.spark.utils.LogLike

/**
 * Represents the hook (interrupt/shutdown) initialization. All JVM-related
 * hooks should be constructed here.
 */
trait HookInitialization {
  /**
   * Initializes and registers all hooks except shutdown.
   *
   * @param interpreter The main interpreter
   */
  def initializeHooks(interpreter: Interpreter): Unit

  /**
   * Initializes the shutdown hook.
   */
  def initializeShutdownHook(): Unit  
}

/**
 * Represents the standard implementation of HookInitialization.
 */
trait StandardHookInitialization extends HookInitialization {
  this: KernelBootstrap with LogLike =>

  /**
   * Initializes and registers all hooks.
   *
   * @param interpreter The main interpreter
   */
  def initializeHooks(interpreter: Interpreter): Unit = {
    registerInterruptHook(interpreter)
  }

  /**
   * Initializes the shutdown hook.
   */
  def initializeShutdownHook(): Unit = {
    registerShutdownHook()
  }  

  private def registerInterruptHook(interpreter: Interpreter): Unit = {
    val self = this

    import sun.misc.{Signal, SignalHandler}

    // TODO: Signals are not a good way to handle this since JVM only has the
    // proprietary sun API that is not necessarily available on all platforms
    Signal.handle(new Signal("INT"), new SignalHandler() {
      private val MaxSignalTime: Long = 3000 // 3 seconds
      var lastSignalReceived: Long    = 0

      def handle(sig: Signal) = {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSignalReceived > MaxSignalTime) {
          logger.info("Resetting code execution!")
          interpreter.interrupt()

          // TODO: Cancel group representing current code execution
          //sparkContext.cancelJobGroup()

          logger.info("Enter Ctrl-C twice to shutdown!")
          lastSignalReceived = currentTime
        } else {
          logger.info("Shutting down kernel")
          self.shutdown()
        }
      }
    })
  }

  private def registerShutdownHook(): Unit = {
    logger.debug("Registering shutdown hook")
    val self = this
    val mainThread = Thread.currentThread()
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() = {
        logger.info("Shutting down kernel")
        self.shutdown()
        // TODO: Check if you can magically access the spark context to stop it
        // TODO: inside a different thread
        if (mainThread.isAlive) mainThread.join()
      }
    })
  }
}
