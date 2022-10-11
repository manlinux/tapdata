package io.tapdata.proxy;

import io.tapdata.entity.annotations.Bean;
import io.tapdata.entity.annotations.Implementation;
import io.tapdata.entity.error.CoreException;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.utils.TypeHolder;
import io.tapdata.modules.api.net.entity.NodeHealth;
import io.tapdata.modules.api.net.error.NetErrors;
import io.tapdata.modules.api.net.service.node.connection.NodeConnection;
import io.tapdata.modules.api.net.service.node.connection.NodeConnectionFactory;
import io.tapdata.pdk.apis.entity.message.CommandInfo;
import io.tapdata.modules.api.net.service.EngineMessageExecutionService;
import io.tapdata.pdk.apis.entity.message.EngineMessage;
import io.tapdata.pdk.apis.entity.message.ServiceCaller;
import io.tapdata.pdk.core.utils.RandomDraw;
import io.tapdata.wsserver.channels.gateway.GatewaySessionHandler;
import io.tapdata.wsserver.channels.gateway.GatewaySessionManager;
import io.tapdata.wsserver.channels.health.NodeHandler;
import io.tapdata.wsserver.channels.health.NodeHealthManager;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@Implementation(EngineMessageExecutionService.class)
public class EngineMessageExecutionServiceImpl implements EngineMessageExecutionService {
	private static final String TAG = EngineMessageExecutionServiceImpl.class.getSimpleName();
	@Bean
	private GatewaySessionManager gatewaySessionManager;
	@Bean
	private NodeHealthManager nodeHealthManager;
	@Bean
	private NodeConnectionFactory nodeConnectionFactory;

	@Override
	public boolean callLocal(EngineMessage commandInfo, BiConsumer<Object, Throwable> biConsumer) {
		return handleEngineMessageInLocal(commandInfo, biConsumer);
	}
	@Override
	public void call(EngineMessage engineMessage, BiConsumer<Object, Throwable> biConsumer) {
		if (handleEngineMessageInLocal(engineMessage, biConsumer)) return;
		send(engineMessage.getClass().getSimpleName(), engineMessage, Object.class, biConsumer);
	}

	private boolean handleEngineMessageInLocal(EngineMessage engineMessage, BiConsumer<Object, Throwable> biConsumer) {
		Collection<GatewaySessionHandler> gatewaySessionHandlers = gatewaySessionManager.getUserIdGatewaySessionHandlerMap().values();
		for(GatewaySessionHandler gatewaySessionHandler : gatewaySessionHandlers) {
			if(gatewaySessionHandler instanceof EngineSessionHandler) {
				EngineSessionHandler engineSessionHandler = (EngineSessionHandler) gatewaySessionHandler;
				boolean bool = false;
				if(engineMessage instanceof CommandInfo) {
					bool = engineSessionHandler.handleCommandInfo((CommandInfo) engineMessage, biConsumer);
				} else if(engineMessage instanceof ServiceCaller) {
					bool = engineSessionHandler.handleServiceCaller((ServiceCaller) engineMessage, biConsumer);
				} else {
					TapLogger.debug(TAG, "No handler for EngineMessage {}", engineMessage);
				}
				if(bool)
					return true;
			}
		}
		return false;
	}


	public <T> void send(String type, EngineMessage engineMessage, TypeHolder<T> typeHolder, BiConsumer<T, Throwable> biConsumer) {
		//noinspection unchecked
		send(type, engineMessage, typeHolder.getType(), biConsumer);
	}
	public <T> void send(String type, EngineMessage engineMessage, Type tClass, BiConsumer<T, Throwable> biConsumer) {
		List<String> list = new ArrayList<>();
		Map<String, NodeHandler> healthyNodes = nodeHealthManager.getIdNodeHandlerMap();
		if(healthyNodes != null) {
			for(NodeHandler nodeHandler : healthyNodes.values()) {
				NodeHealth nodeHealth = nodeHandler.getNodeHealth();
				NodeHealth currentNodeHealth = nodeHealthManager.getCurrentNodeHealth();
				if(!currentNodeHealth.getId().equals(nodeHealth.getId()) && nodeHealth.getOnline() != null && nodeHealth.getOnline() > 0) {
					list.add(nodeHealth.getId());
				}
			}
		}

		Throwable error = null;
		if(!list.isEmpty()) {
			RandomDraw randomDraw = new RandomDraw(list.size());
			int index;
			while ((index = randomDraw.next()) != -1) {
				String id = list.get(index);
				NodeConnection nodeConnection = nodeConnectionFactory.getNodeConnection(id);
				if (nodeConnection != null && nodeConnection.isReady()) {
					try {
						//noinspection unchecked
						T response = nodeConnection.send(type, engineMessage, tClass);
						biConsumer.accept(response, null);
						return;
					} catch (IOException ioException) {
						TapLogger.debug(TAG, "Send to nodeId {} failed {} and will try next, command {}", id, ioException.getMessage(), engineMessage);
						error = ioException;
					}
				}
			}
		}
		if(error != null) {
			biConsumer.accept(null, error);
		} else {
			biConsumer.accept(null, new CoreException(NetErrors.NO_AVAILABLE_ENGINE, "No available engine"));
		}
	}
}
