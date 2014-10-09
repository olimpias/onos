package org.onlab.onos.store.cluster.messaging.impl;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.util.Set;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.cluster.ClusterService;
import org.onlab.onos.cluster.ControllerNode;
import org.onlab.onos.cluster.NodeId;
import org.onlab.onos.store.cluster.messaging.ClusterCommunicationService;
import org.onlab.onos.store.cluster.messaging.ClusterMessage;
import org.onlab.onos.store.cluster.messaging.ClusterMessageHandler;
import org.onlab.onos.store.cluster.messaging.MessageSubject;
import org.onlab.onos.store.serializers.KryoPoolUtil;
import org.onlab.onos.store.serializers.KryoSerializer;
import org.onlab.util.KryoPool;
import org.onlab.netty.Endpoint;
import org.onlab.netty.Message;
import org.onlab.netty.MessageHandler;
import org.onlab.netty.MessagingService;
import org.onlab.netty.NettyMessagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true)
@Service
public class ClusterCommunicationManager
        implements ClusterCommunicationService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ControllerNode localNode;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private ClusterService clusterService;

    // TODO: This probably should not be a OSGi service.
    private MessagingService messagingService;

    private static final KryoSerializer SERIALIZER = new KryoSerializer() {
        @Override
        protected void setupKryoPool() {
            serializerPool = KryoPool.newBuilder()
                    .register(KryoPoolUtil.API)
                    .register(ClusterMessage.class, new ClusterMessageSerializer())
                    .register(byte[].class)
                    .register(MessageSubject.class, new MessageSubjectSerializer())
                    .build()
                    .populate(1);
        }

    };

    @Activate
    public void activate() {
        localNode = clusterService.getLocalNode();
        NettyMessagingService netty = new NettyMessagingService(localNode.tcpPort());
        // FIXME: workaround until it becomes a service.
        try {
            netty.activate();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            log.error("NettyMessagingService#activate", e);
        }
        messagingService = netty;
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        // TODO: cleanup messageingService if needed.
        log.info("Stopped");
    }

    @Override
    public boolean broadcast(ClusterMessage message) {
        boolean ok = true;
        for (ControllerNode node : clusterService.getNodes()) {
            if (!node.equals(localNode)) {
                ok = unicast(message, node.id()) && ok;
            }
        }
        return ok;
    }

    @Override
    public boolean multicast(ClusterMessage message, Set<NodeId> nodes) {
        boolean ok = true;
        for (NodeId nodeId : nodes) {
            if (!nodeId.equals(localNode.id())) {
                ok = unicast(message, nodeId) && ok;
            }
        }
        return ok;
    }

    @Override
    public boolean unicast(ClusterMessage message, NodeId toNodeId) {
        ControllerNode node = clusterService.getNode(toNodeId);
        checkArgument(node != null, "Unknown nodeId: %s", toNodeId);
        Endpoint nodeEp = new Endpoint(node.ip().toString(), node.tcpPort());
        try {
            messagingService.sendAsync(nodeEp,
                    message.subject().value(), SERIALIZER.encode(message));
            return true;
        } catch (IOException e) {
            log.error("Failed to send cluster message to nodeId: " + toNodeId, e);
        }

        return false;
    }

    @Override
    public void addSubscriber(MessageSubject subject,
            ClusterMessageHandler subscriber) {
        messagingService.registerHandler(subject.value(), new InternalClusterMessageHandler(subscriber));
    }

    private final class InternalClusterMessageHandler implements MessageHandler {

        private final ClusterMessageHandler handler;

        public InternalClusterMessageHandler(ClusterMessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void handle(Message message) {
            try {
                ClusterMessage clusterMessage = SERIALIZER.decode(message.payload());
                handler.handle(clusterMessage);
            } catch (Exception e) {
                log.error("Exception caught during ClusterMessageHandler", e);
                throw e;
            }
        }
    }
}