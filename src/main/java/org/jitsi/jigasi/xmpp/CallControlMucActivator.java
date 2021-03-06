/*
 * Jigasi, the JItsi GAteway to SIP.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jigasi.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.rayo.RayoIqProvider.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.service.protocol.jabber.*;
import net.java.sip.communicator.util.*;
import org.jitsi.jigasi.*;
import org.jitsi.jigasi.util.*;
import org.jitsi.service.configuration.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.osgi.framework.*;

import java.util.*;

/**
 * Call control that is capable of utilizing Rayo XMPP protocol for the purpose
 * of SIP gateway calls management.
 * This is based on muc called brewery, where all Jigasi instances join and
 * publish their usage stats using <tt>ColibriStatsExtension</tt> in presence.
 * The focus use the stats to load balance between instances.
 *
 * @author Damian Minkov
 * @author Nik Vaessen
 */
public class CallControlMucActivator
    implements BundleActivator,
               ServiceListener,
               RegistrationStateChangeListener,
               GatewaySessionListener,
               GatewayListener,
               PacketFilter
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(CallControlMucActivator.class);

    private static BundleContext osgiContext;

    /**
     * The account property to search in configuration service for the room name
     * where all xmpp providers will join.
     */
    private static final String ROOM_NAME_ACCOUNT_PROP = "BREWERY";

    /**
     * A property to enable or disable muc call control, disabled by default.
     */
    private static final String BREWERY_ENABLED_PROP
        = "org.jitsi.jigasi.BREWERY_ENABLED";

    /**
     * The call controlling logic.
     */
    private CallControl callControl = null;

    /**
     * Starts muc control component. Finds all xmpp accounts and listen for
     * new ones registered.
     * @param bundleContext the bundle context
     */
    @Override
    public void start(final BundleContext bundleContext)
        throws Exception
    {
        osgiContext = bundleContext;

        ConfigurationService config = getConfigurationService();

        if (!config.getBoolean(BREWERY_ENABLED_PROP, false))
        {
            logger.warn("MUC call control disabled.");
            return;
        }

        osgiContext.addServiceListener(this);

        Collection<ServiceReference<ProtocolProviderService>> refs
            = ServiceUtils.getServiceReferences(
                osgiContext,
                ProtocolProviderService.class);

        for (ServiceReference<ProtocolProviderService> ref : refs)
        {
            initializeNewProvider(osgiContext.getService(ref));
        }

        SipGateway sipGateway = ServiceUtils.getService(
            bundleContext, SipGateway.class);

        TranscriptionGateway transcriptionGateway = ServiceUtils.getService(
                bundleContext, TranscriptionGateway.class);

        this.callControl = new CallControl(config);

        if (sipGateway != null)
        {
            sipGateway.addGatewayListener(this);
            this.callControl.setSipGateway(sipGateway);
        }
        if (transcriptionGateway != null)
        {
            transcriptionGateway.addGatewayListener(this);
            this.callControl.setTranscriptionGateway(transcriptionGateway);
        }
    }

    /**
     * Stops and unregister everything - providers, listeners.
     * @param bundleContext the bundle context
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        osgiContext.removeServiceListener(this);

        Collection<ServiceReference<ProtocolProviderService>> refs
            = ServiceUtils.getServiceReferences(
                osgiContext,
                ProtocolProviderService.class);

        for (ServiceReference<ProtocolProviderService> ref : refs)
        {
            ProtocolProviderService pps = osgiContext.getService(ref);
            if (ProtocolNames.JABBER.equals(pps.getProtocolName()))
            {
                try
                {
                    pps.unregister();
                }
                catch(OperationFailedException e)
                {
                    logger.error("Cannot unregister xmpp provider", e);
                }
            }
        }
    }

    /**
     * Returns <tt>ConfigurationService</tt> instance.
     * @return <tt>ConfigurationService</tt> instance.
     */
    private static ConfigurationService getConfigurationService()
    {
        return ServiceUtils.getService(
            osgiContext, ConfigurationService.class);
    }

    @Override
    public void serviceChanged(ServiceEvent serviceEvent)
    {
        if (serviceEvent.getType() != ServiceEvent.REGISTERED)
            return;

        ServiceReference ref = serviceEvent.getServiceReference();

        Object service = osgiContext.getService(ref);

        if (service instanceof ProtocolProviderService)
        {
            initializeNewProvider((ProtocolProviderService) service);
        }
        else if (service instanceof SipGateway)
        {
            SipGateway gateway = (SipGateway) service;
            gateway.addGatewayListener(this);

            if (this.callControl == null)
            {
                this.callControl = new CallControl(
                        gateway, getConfigurationService());
            }
            else
            {
                this.callControl.setSipGateway(gateway);
            }
        }
        else if (service instanceof TranscriptionGateway)
        {
            TranscriptionGateway gateway = (TranscriptionGateway) service;
            gateway.addGatewayListener(this);

            if (this.callControl == null)
            {
                this.callControl = new CallControl(
                        gateway, getConfigurationService());
            }
            else
            {
                this.callControl.setTranscriptionGateway(gateway);
            }
        }
    }

    /**
     * Initialize newly registered into osgi protocol provider.
     * @param pps an xmpp protocol provider
     */
    private void initializeNewProvider(ProtocolProviderService pps)
    {
        if (!ProtocolNames.JABBER.equals(pps.getProtocolName())
            || (pps.getAccountID() instanceof JabberAccountID
                    && ((JabberAccountID)pps.getAccountID())
                        .isAnonymousAuthUsed()))
        {
            // we do not care for anonymous logins, these are normally the xmpp
            // sessions in a call
            // while the call control xmpp accounts are authorized
            return;
        }

        pps.addRegistrationStateChangeListener(this);

        ProtocolProviderFactory xmppProviderFactory
            = ProtocolProviderFactory.getProtocolProviderFactory(
                JigasiBundleActivator.osgiContext,
                ProtocolNames.JABBER);

        new RegisterThread(
            pps, xmppProviderFactory.loadPassword(pps.getAccountID()))
                .start();
    }

    /**
     * When a xmpp provder is registered to xmpp server we join it to the
     * common room.
     *
     * @param evt the registration event
     */
    @Override
    public void registrationStateChanged(RegistrationStateChangeEvent evt)
    {
        if (evt.getNewState() == RegistrationState.REGISTERED)
        {
            joinCommonRoom(evt.getProvider());
        }
    }

    /**
     * Joins the common control room.
     * @param pps the provider to join.
     */
    private void joinCommonRoom(ProtocolProviderService pps)
    {
        OperationSetMultiUserChat muc
            = pps.getOperationSet(OperationSetMultiUserChat.class);

        String roomName = pps.getAccountID()
            .getAccountPropertyString(ROOM_NAME_ACCOUNT_PROP);

        if (roomName == null)
        {
            logger.warn("No brewery name specified for:" + pps);
            return;
        }

        try
        {
            logger.info(
                "Joining call control room: " + roomName + " pps:" + pps);

            ChatRoom mucRoom = muc.findRoom(roomName);

            mucRoom.join();

            // sends initial stats, used some kind of advertising
            // so jicofo can recognize us as real jigasi and load balance us
            updatePresenceStatusForXmppProvider(pps);

            // getting direct access to the xmpp connection in order to add
            // a listener for incoming iqs
            if (pps instanceof ProtocolProviderServiceJabberImpl)
            {
                // we do not care for removing the packet listener
                // as its added to the connection, if the protocol provider
                // gets disconnected and connects again it should create new
                // connection and scrap old
                ((ProtocolProviderServiceJabberImpl) pps).getConnection()
                    .addPacketListener(new PProviderPacketListener(pps), this);
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
        }
    }

    @Override
    public void onJvbRoomJoined(AbstractGatewaySession source)
    {
        updatePresenceStatusForXmppProviders();
    }

    @Override
    public void onSessionAdded(AbstractGatewaySession session)
    {
        session.addListener(this);
        // we are not updating anything here (stats), cause session was just
        // created and we haven't joined the jvb room so there is no change
        // in participant count and the conference is actually not started yet
        // till we join
    }

    @Override
    public void onSessionRemoved(AbstractGatewaySession session)
    {
        updatePresenceStatusForXmppProviders();
        session.removeListener(this);
    }

    @Override
    public void onSessionFailed(AbstractGatewaySession session)
    {}

    /**
     * Gets the list of active sessions and update their presence in their
     * control room.
     * Counts number of active sessions as conference count, and number of
     * all participants in all jvb rooms as global participant count.
     */
    private void updatePresenceStatusForXmppProviders()
    {
        SipGateway gateway = ServiceUtils.getService(
            osgiContext, SipGateway.class);

        int participants = 0;
        for(SipGatewaySession ses : gateway.getActiveSessions())
        {
            participants += ses.getJvbChatRoom().getMembersCount();
        }

        Collection<ServiceReference<ProtocolProviderService>> refs
            = ServiceUtils.getServiceReferences(
                osgiContext,
                ProtocolProviderService.class);

        for (ServiceReference<ProtocolProviderService> ref : refs)
        {
            updatePresenceStatusForXmppProvider(
                gateway, osgiContext.getService(ref), participants);
        }
    }

    /**
     * Sends <tt>ColibriStatsExtension</tt> as part of presence with
     * load information for the <tt>ProtocolProviderService</tt> in its
     * brewery room.
     *
     * @param gateway the <tt>SipGateway</tt> instance we serve.
     * @param pps the protocol provider service
     * @param participantsCount the participant count.
     */
    private void updatePresenceStatusForXmppProvider(
        SipGateway gateway,
        ProtocolProviderService pps,
        int participantsCount)
    {
        if (ProtocolNames.JABBER.equals(pps.getProtocolName())
            && pps.getAccountID() instanceof JabberAccountID
            && !((JabberAccountID)pps.getAccountID()).isAnonymousAuthUsed())
        {
            try
            {
                String roomName = pps.getAccountID()
                    .getAccountPropertyString(ROOM_NAME_ACCOUNT_PROP);
                if (roomName == null)
                {
                    return;
                }

                OperationSetMultiUserChat muc
                    = pps.getOperationSet(OperationSetMultiUserChat.class);
                ChatRoom mucRoom = muc.findRoom(roomName);

                if (mucRoom == null)
                    return;

                ColibriStatsExtension stats = new ColibriStatsExtension();
                stats.addStat(new ColibriStatsExtension.Stat("conferences",
                    gateway.getActiveSessions().size()));
                stats.addStat(new ColibriStatsExtension.Stat("participants",
                    participantsCount));

                pps.getOperationSet(OperationSetJitsiMeetTools.class)
                    .sendPresenceExtension(mucRoom, stats);
            }
            catch (Exception e)
            {
                logger.error("Error updating presence for:" + pps, e);
            }
        }
    }

    /**
     * Counts number of active sessions as conference count, and number of
     * all participants in all jvb rooms as global participant count.
     * And updates the presence of the <tt>ProtocolProviderService</tt> that
     * is specified.
     * @param pps the protocol provider to update.
     */
    private void updatePresenceStatusForXmppProvider(
        ProtocolProviderService pps)
    {
        SipGateway gateway = ServiceUtils.getService(
            osgiContext, SipGateway.class);

        int participants = 0;
        for(SipGatewaySession ses : gateway.getActiveSessions())
        {
            participants += ses.getJvbChatRoom().getMembersCount();
        }

        updatePresenceStatusForXmppProvider(gateway, pps, participants);
    }

    /**
     * Accepts only  {@link RayoIq}.
     * {@inheritDoc}
     */
    @Override
    public boolean accept(Packet packet)
    {
        return packet instanceof RayoIq;
    }

    /**
     * Packet listener per protocol provider. Used to get the custom
     * bosh URL property from its account properties and to pass it when
     * processing incoming iq.
     */
    private class PProviderPacketListener
        implements PacketListener
    {
        private final ProtocolProviderService pps;

        PProviderPacketListener(ProtocolProviderService pps)
        {
            this.pps = pps;
        }

        @Override
        public void processPacket(Packet packet)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Processing a RayoIq: " + packet.toXML());
            }

            IQ resultIQ;
            try
            {
                if (packet instanceof HangUp)
                {
                    // hangup is not currently supported to be send to
                    // muc controller
                    resultIQ = IQ.createResultIQ((IQ)packet);
                    resultIQ.setError(new XMPPError(
                        XMPPError.Condition.feature_not_implemented));
                }
                else
                {
                    AccountID acc = pps.getAccountID();

                    final CallContext ctx = new CallContext(pps);
                    ctx.setDomain(acc.getAccountPropertyString(
                        CallContext.DOMAIN_BASE_ACCOUNT_PROP));
                    ctx.setBoshURL(acc.getAccountPropertyString(
                        CallContext.BOSH_URL_ACCOUNT_PROP));
                    ctx.setMucAddressPrefix(acc.getAccountPropertyString(
                        CallContext.MUC_DOMAIN_PREFIX_PROP, "conference"));

                    resultIQ = callControl.handleIQ((IQ) packet, ctx);
                    final RefIq response = (RefIq)resultIQ;

                    final AbstractGatewaySession sess =
                        callControl.getSession(ctx.getCallResource());
                    if (sess != null)
                    {
                        sendDialResponse(response, sess);
                    }
                    else
                    {
                        callControl.addGatewayListener(new GatewayListener()
                        {
                            @Override
                            public void onSessionAdded(
                                AbstractGatewaySession session)
                            {
                                if (session.getCallContext().equals(ctx))
                                {
                                    sendDialResponse(response, session);
                                    // we had processed the dial response
                                    // no more interested in this events
                                    callControl.removeGatewayListener(this);
                                }
                            }

                            @Override
                            public void onSessionRemoved(
                                AbstractGatewaySession session)
                            {}

                            @Override
                            public void onSessionFailed(
                                AbstractGatewaySession session)
                            {
                                if (session.getCallContext().equals(ctx))
                                {
                                    callControl.removeGatewayListener(this);
                                }
                            }
                        });
                    }

                    return;
                }
            }
            catch (Exception e)
            {
                logger.error("Error processing RayoIq", e);
                resultIQ = IQ.createResultIQ((IQ)packet);
                resultIQ.setError(new XMPPError(
                    XMPPError.Condition.interna_server_error, e.getMessage()));
            }

            // send response
            ((ProtocolProviderServiceJabberImpl) pps).getConnection()
                .sendPacket(resultIQ);
        }

        /**
         * Sends the dial response by replacing the uri in RefIq response
         * by placing there the address of the muc participant.
         * This way the participant can receive hangup iqs and we listen for
         * them and handle them.
         * We do not care for removing listeners there cause they are added to
         * the xmpp protocol provider which will be unregistered and removed
         * from osgi.
         *
         * @param response the response to modify and send.
         * @param session the session created that needs that response.
         */
        private void sendDialResponse(
            RefIq response, final AbstractGatewaySession session)
        {
            ChatRoom room = session.getJvbChatRoom();
            response.setUri(
                "xmpp:" + room.getIdentifier() + "/" + room.getUserNickname());

            // we send the response where we have received using the provider
            // registered in the control muc
            ((ProtocolProviderServiceJabberImpl) pps)
                .getConnection().sendPacket(response);

            final Connection roomConnection
                = ((ProtocolProviderServiceJabberImpl) room.getParentProvider())
                    .getConnection();

            roomConnection.addPacketListener(
                packet ->
                {
                    HangUp hangUpIq = (HangUp) packet;
                    session.hangUp();
                    roomConnection.sendPacket(IQ.createResultIQ(hangUpIq));
                },
                packet -> packet instanceof HangUp
            );
        }
    }
}