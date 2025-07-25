/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.graph.controller.process;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.async.AsyncGenerator;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Graph Processor
 *
 * Responsible for processing graph streaming output and converting NodeOutput to SSE
 * events. Handles both regular node outputs and streaming outputs with proper event
 * formatting.
 *
 * Features: - Streaming output processing - SSE event formatting - Asynchronous execution
 * - Error handling and logging
 *
 * @author sixiyida
 */
public class GraphProcess {

	private static final Logger logger = LoggerFactory.getLogger(GraphProcess.class);

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	/**
	 * Constructor for GraphProcess
	 *
	 */
	public GraphProcess() {
	}

	/**
	 * Reactor-friendly streaming output processor.
	 *
	 * 将 AsyncGenerator<NodeOutput> 转为 Flux<ServerSentEvent<String>>， 保证链路追踪上下文不丢失。
	 * @param generator the async generator providing node outputs
	 * @return Flux of SSE events
	 */
	public Flux<ServerSentEvent<String>> processStream(AsyncGenerator<NodeOutput> generator) {
		return Flux.create(sink -> processNext(generator, sink));
	}

	private void processNext(AsyncGenerator<NodeOutput> generator,
			reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink) {
		AsyncGenerator.Data<NodeOutput> data = generator.next();
		logger.info("processNext called: isDone={}, isError={}, data={} ", data.isDone(), data.isError(), data);
		if (data.isDone()) {
			logger.info("processNext: Graph processing completed");
			sink.next(ServerSentEvent.builder("{\"type\":\"completed\",\"message\":\"Graph processing completed\"}")
				.event("completed")
				.build());
			sink.complete();
			return;
		}
		if (data.isError()) {
			data.getData().whenComplete((v, ex) -> {
				logger.error("processNext: Error occurred in data.getData()", ex);
				sink.next(ServerSentEvent.builder("{\"type\":\"error\",\"message\":\"" + ex.getMessage() + "\"}")
					.event("error")
					.build());
				sink.error(ex);
			});
			return;
		}
		// 正常节点输出
		data.getData().whenComplete((output, ex) -> {
			if (ex != null) {
				logger.error("processNext: Exception in output", ex);
				sink.next(ServerSentEvent.builder("{\"type\":\"error\",\"message\":\"" + ex.getMessage() + "\"}")
					.event("error")
					.build());
				sink.error(ex);
			}
			else {
				logger.info("processNext: output node={}, output class={}, output={}",
						output != null ? output.node() : null, output != null ? output.getClass().getName() : null,
						output);
				String content;
				if (output instanceof StreamingOutput streamingOutput) {
					content = JSON.toJSONString(Map.of("type", "streaming", "node", output.node(), "chunk",
							streamingOutput.chunk(), "timestamp", System.currentTimeMillis()));
				}
				else {
					JSONObject nodeOutput = new JSONObject();
					nodeOutput.put("type", "node_output");
					nodeOutput.put("node", output.node());
					nodeOutput.put("data", output.state().data());
					nodeOutput.put("timestamp", System.currentTimeMillis());
					content = JSON.toJSONString(nodeOutput);
				}
				logger.info("processNext: emitting SSE event for node {}", output != null ? output.node() : null);
				sink.next(ServerSentEvent.builder(content)
					.event("node_output")
					.id(output.node() + "_" + System.currentTimeMillis())
					.build());
				// 递归推进
				processNext(generator, sink);
			}
		});
	}

}