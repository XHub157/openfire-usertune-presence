/*
 * openfire-usertune-presence
 * Copyright (C) 2026 XHub157
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.example.openfire.plugin;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.PacketException;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.roster.Roster;
import org.jivesoftware.openfire.roster.RosterItem;
import org.jivesoftware.openfire.roster.RosterManager;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.Presence;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UserTunePresencePlugin
 *
 * Реализует поведение ejabberd для User Tune (XEP-0118):
 *
 * 1. При публикации трека — рассылает PEP-нотификацию всем онлайн-контактам
 *    из ростера напрямую на их ресурсы (минуя CAPS-проверку Openfire).
 *
 * 2. При появлении контакта онлайн (presence available) — отправляет ему
 *    последний опубликованный трек пользователя (send_last_published_item).
 *    Именно так работает ejabberd: клиент получает актуальный трек сразу
 *    при установлении присутствия, не дожидаясь следующей публикации.
 *
 * Последние треки хранятся в памяти (ConcurrentHashMap, bareJID → tune Element).
 * При остановке плагина кэш очищается.
 */
public class UserTunePresencePlugin implements Plugin, PacketInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(UserTunePresencePlugin.class);

    private static final String NS_PUBSUB = "http://jabber.org/protocol/pubsub";
    private static final String NS_TUNE   = "http://jabber.org/protocol/tune";
    private static final String NS_EVENT  = "http://jabber.org/protocol/pubsub#event";

    /** Стандартные поля XEP-0118 в правильном порядке. */
    private static final String[] STANDARD_FIELDS =
            {"artist", "title", "album", "track", "length", "rating", "uri"};

    /**
     * Кэш последних треков: bareJID → запись (ресурс-публикатор + tune элемент).
     * Храним полный JID ресурса чтобы знать с какого именно клиента пришёл трек.
     */
    private static class TuneEntry {
        final String publisherResource; // полный JID ресурса который опубликовал
        final Element tune;
        TuneEntry(String publisherResource, Element tune) {
            this.publisherResource = publisherResource;
            this.tune = tune;
        }
    }

    private final ConcurrentHashMap<String, TuneEntry> lastTune = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Plugin lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        InterceptorManager.getInstance().addInterceptor(this);
        LOG.info("UserTunePresencePlugin: зарегистрирован интерцептор.");
    }

    @Override
    public void destroyPlugin() {
        InterceptorManager.getInstance().removeInterceptor(this);
        lastTune.clear();
        LOG.info("UserTunePresencePlugin: интерцептор удалён.");
    }

    // -------------------------------------------------------------------------
    // PacketInterceptor
    // -------------------------------------------------------------------------

    @Override
    public void interceptPacket(Packet packet,
                                Session session,
                                boolean incoming,
                                boolean processed)
            throws PacketRejectedException {

        if (!incoming || processed) return;
        if (!(session instanceof ClientSession)) return;

        if (packet instanceof IQ) {
            handleIQ((IQ) packet, session);
        } else if (packet instanceof Presence) {
            handlePresence((Presence) packet, session);
        }
    }

    // -------------------------------------------------------------------------
    // Обработка IQ-публикации tune
    // -------------------------------------------------------------------------

    private void handleIQ(IQ iq, Session session) {
        Element pubsub = iq.getChildElement();
        if (pubsub == null) return;
        if (!NS_PUBSUB.equals(pubsub.getNamespaceURI())
                || !"pubsub".equals(pubsub.getName())) return;

        Element publish = pubsub.element("publish");
        if (publish == null) return;
        if (!NS_TUNE.equals(publish.attributeValue("node"))) return;

        Element item = publish.element("item");
        if (item == null) return;

        Element tune = item.element(QName.get("tune", NS_TUNE));
        if (tune == null) tune = item.element("tune");
        if (tune == null) return;

        String itemId = item.attributeValue("id");
        if (itemId == null) itemId = "current";

        JID publisherJID = session.getAddress();
        String bareJID = publisherJID.toBareJID();

        // Нормализуем tune — только стандартные поля XEP-0118
        Element normalizedTune = normalizeTune(tune);

        // Сохраняем в кэш с привязкой к ресурсу-публикатору
        if (normalizedTune.elements().isEmpty()) {
            lastTune.remove(bareJID);
            LOG.info("UserTunePresencePlugin: {} остановил плеер", bareJID);
        } else {
            lastTune.put(bareJID, new TuneEntry(publisherJID.toString(), normalizedTune));
            LOG.info("UserTunePresencePlugin: {} публикует tune, рассылаем контактам", bareJID);
        }

        broadcastTuneToRoster(publisherJID, normalizedTune, itemId);
    }

    // -------------------------------------------------------------------------
    // Обработка presence — send_last_published_item
    // -------------------------------------------------------------------------

    private void handlePresence(Presence presence, Session session) {
        Presence.Type type = presence.getType();

        // Клиент уходит офлайн — очищаем его трек и оповещаем контактов
        if (type == Presence.Type.unavailable) {
            JID publisherJID = session.getAddress();
            String bareJID = publisherJID.toBareJID();
            String fullJID = publisherJID.toString();

            TuneEntry entry = lastTune.get(bareJID);
            if (entry != null && fullJID.equals(entry.publisherResource)) {
                SessionManager sessionManager = XMPPServer.getInstance().getSessionManager();
                Collection<ClientSession> remaining =
                        sessionManager.getSessions(publisherJID.getNode());
                long otherSessions = remaining == null ? 0 :
                        remaining.stream()
                                 .filter(s -> !s.getAddress().equals(publisherJID))
                                 .count();

                if (otherSessions == 0) {
                    lastTune.remove(bareJID);
                    Element emptyTune = DocumentHelper.createElement(
                            QName.get("tune", NS_TUNE));
                    broadcastTuneToRoster(publisherJID, emptyTune, "current");
                    LOG.info("UserTunePresencePlugin: {} офлайн, tune очищен", bareJID);
                } else {
                    remaining.stream()
                             .filter(s -> !s.getAddress().equals(publisherJID))
                             .findFirst()
                             .ifPresent(s -> lastTune.put(bareJID,
                                     new TuneEntry(s.getAddress().toString(), entry.tune)));
                    LOG.debug("UserTunePresencePlugin: {} ушёл, tune перепривязан", fullJID);
                }
            }
            return;
        }

        // available presence — send_last_published_item
        if (type != null) return;
        JID contactJID = session.getAddress();

        if (!XMPPServer.getInstance().isLocal(contactJID)) return;

        RosterManager rosterManager = XMPPServer.getInstance().getRosterManager();
        try {
            Roster roster = rosterManager.getRoster(contactJID.getNode());
            for (RosterItem rosterItem : roster.getRosterItems()) {
                RosterItem.SubType sub = rosterItem.getSubStatus();
                if (sub != RosterItem.SubType.BOTH
                        && sub != RosterItem.SubType.TO) {
                    continue;
                }

                String publisherBare = rosterItem.getJid().toBareJID();
                TuneEntry entry = lastTune.get(publisherBare);
                if (entry == null || entry.tune.elements().isEmpty()) continue;

                try {
                    XMPPServer.getInstance().getMessageRouter().route(
                            buildTuneNotification(
                                    rosterItem.getJid().asBareJID(),
                                    contactJID, entry.tune, "current"));
                    LOG.debug("UserTunePresencePlugin: last tune {} → {}",
                              publisherBare, contactJID);
                } catch (PacketException e) {
                    LOG.warn("UserTunePresencePlugin: ошибка last tune → {}", contactJID, e);
                }
            }
        } catch (UserNotFoundException e) {
            LOG.debug("UserTunePresencePlugin: ростер не найден для {}", contactJID.toBareJID());
        }
    }

    // -------------------------------------------------------------------------
    // Рассылка
    // -------------------------------------------------------------------------

    private void broadcastTuneToRoster(JID publisherJID, Element tune, String itemId) {
        RosterManager rosterManager = XMPPServer.getInstance().getRosterManager();
        SessionManager sessionManager = XMPPServer.getInstance().getSessionManager();
        JID bareJID = publisherJID.asBareJID();
        int sent = 0;

        try {
            Roster roster = rosterManager.getRoster(publisherJID.getNode());
            for (RosterItem contact : roster.getRosterItems()) {
                RosterItem.SubType sub = contact.getSubStatus();
                if (sub != RosterItem.SubType.BOTH
                        && sub != RosterItem.SubType.FROM) {
                    continue;
                }

                JID contactBareJID = contact.getJid();

                if (XMPPServer.getInstance().isLocal(contactBareJID)) {
                    Collection<ClientSession> sessions =
                            sessionManager.getSessions(contactBareJID.getNode());
                    if (sessions != null) {
                        for (ClientSession cs : sessions) {
                            JID fullJID = cs.getAddress();
                            try {
                                XMPPServer.getInstance().getMessageRouter()
                                        .route(buildTuneNotification(bareJID, fullJID, tune, itemId));
                                sent++;
                            } catch (PacketException e) {
                                LOG.warn("UserTunePresencePlugin: ошибка → {}", fullJID, e);
                            }
                        }
                    }
                } else {
                    try {
                        XMPPServer.getInstance().getMessageRouter()
                                .route(buildTuneNotification(bareJID, contactBareJID, tune, itemId));
                        sent++;
                    } catch (PacketException e) {
                        LOG.warn("UserTunePresencePlugin: ошибка → {}", contactBareJID, e);
                    }
                }
            }
        } catch (UserNotFoundException e) {
            LOG.warn("UserTunePresencePlugin: ростер не найден для {}", publisherJID.getNode());
        }

        // Другие ресурсы самого публикатора
        Collection<ClientSession> selfSessions =
                sessionManager.getSessions(publisherJID.getNode());
        if (selfSessions != null) {
            for (ClientSession cs : selfSessions) {
                JID fullJID = cs.getAddress();
                if (fullJID.equals(publisherJID)) continue;
                try {
                    XMPPServer.getInstance().getMessageRouter()
                            .route(buildTuneNotification(bareJID, fullJID, tune, itemId));
                    sent++;
                } catch (PacketException e) {
                    LOG.warn("UserTunePresencePlugin: self-ошибка → {}", fullJID, e);
                }
            }
        }

        LOG.info("UserTunePresencePlugin: tune-нотификация отправлена {} ресурсам", sent);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Message buildTuneNotification(JID from, JID to,
                                          Element tune, String itemId) {
        Message msg = new Message();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setType(Message.Type.headline);

        Element event = msg.getElement().addElement(QName.get("event", NS_EVENT));
        Element items = event.addElement("items");
        items.addAttribute("node", NS_TUNE);
        Element item = items.addElement("item");
        item.addAttribute("id", itemId);
        item.add(tune.createCopy());

        return msg;
    }

    private Element normalizeTune(Element tune) {
        Element result = DocumentHelper.createElement(QName.get("tune", NS_TUNE));
        for (String field : STANDARD_FIELDS) {
            Element src = tune.element(QName.get(field, NS_TUNE));
            if (src == null) src = tune.element(field);
            if (src != null) {
                String val = src.getTextTrim();
                if (val != null && !val.isEmpty()) {
                    result.addElement(field).setText(val);
                }
            }
        }
        return result;
    }
}
