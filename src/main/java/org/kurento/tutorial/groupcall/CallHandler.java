/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.groupcall;

import java.io.IOException;
import java.util.Map;

import com.google.gson.JsonPrimitive;
import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 *
 * @author Ivan Gracia (izanmail@gmail.com)
 * @since 4.3.1
 */
public class CallHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(CallHandler.class);

  private static final Gson gson = new GsonBuilder().create();

  @Autowired
  private RoomManager roomManager;

  @Autowired
  private UserRegistry registry;

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    final JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);

    final UserSession user = registry.getBySession(session);

    if (user != null) {
      log.debug("Incoming message from user '{}': {}", user.getName(), jsonMessage);
    } else {
      log.debug("Incoming message from new user: {}", jsonMessage);
    }

    switch (jsonMessage.get("id").getAsString()) {
      case "joinRoom":
        joinRoom(jsonMessage, session);
        break;
      case "receiveVideoFrom":
        final String senderName = jsonMessage.get("sender").getAsString();
        final UserSession sender = registry.getByName(senderName);
        final String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
        user.receiveVideoFrom(sender, sdpOffer);
        break;
      case "leaveRoom":
        leaveRoom(user);
        break;
      case "onIceCandidate":
        JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();

        if (user != null) {
          IceCandidate cand = new IceCandidate(candidate.get("candidate").getAsString(),
              candidate.get("sdpMid").getAsString(), candidate.get("sdpMLineIndex").getAsInt());
          user.addCandidate(cand, jsonMessage.get("name").getAsString());
        }
        break;
      case "startFilter":
        String imageURL =
                "https://raw.githubusercontent.com/Kurento/test-files/main/img/mario-wings.png";
        applyFaceOverlayFilter(session, imageURL);
        break;

      case "muteSelf":
        boolean mute = jsonMessage.get("mute").getAsBoolean();
        user.setMuted(mute); // Store state per user session

        WebRtcEndpoint senderEp = user.getOutgoingWebRtcPeer();

        for (UserSession remote : registry.getUsersByName().values()) {
          if (!remote.getName().equals(user.getName())) {
            WebRtcEndpoint recvEp = remote.getIncomingMedia().get(user.getName());
            if (mute) {
              senderEp.disconnect(recvEp, MediaType.AUDIO);
            } else {
              senderEp.connect(recvEp, MediaType.AUDIO);
            }
          }
        }
        break;

      case "chat":
        final String id = "receiveTextAnswer";
        final String textMessageSender = jsonMessage.get("sender").getAsString();
        final String textMessageContent = jsonMessage.get("content").getAsString();

        JsonObject response = new JsonObject();
        response.addProperty("id", id);
        response.addProperty("sender", textMessageSender);
        response.addProperty("content", textMessageContent);

        registry.getUsersByName().entrySet().forEach(stringUserSessionEntry -> {
          try {
            UserSession currentUserSession = stringUserSessionEntry.getValue();
            boolean isInTheSameRoom = user.getRoomName().equals(currentUserSession.getRoomName());
            if(isInTheSameRoom)
              stringUserSessionEntry.getValue().sendMessage(response);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });

        break;
      default:
        break;
    }
  }

  /***
   * @param session of the current user
   * @param imageUrl image that is placed over the head
   * @throws IOException
   */
  public void applyFaceOverlayFilter(WebSocketSession session, String imageUrl) throws IOException {
    UserSession user = registry.getBySession(session);
    if (user == null) return;

    // Reuse existing filter if present
    FaceOverlayFilter filter = (FaceOverlayFilter) user.getAppliedFilter();
    if (filter == null) {
      filter = new FaceOverlayFilter.Builder(user.getPipeline()).build();
      user.setAppliedFilter(filter);
    }

    filter.setOverlayedImage(imageUrl, -0.35F, -1.2F, 1.6F, 1.6F);

    WebRtcEndpoint senderEp = user.getOutgoingWebRtcPeer();
    WebRtcEndpoint selfPreview = user.getSelfPreviewEndpoint();

    // Reconnect self preview
    senderEp.disconnect(selfPreview, MediaType.VIDEO);
    senderEp.connect(filter, MediaType.VIDEO);
    filter.connect(selfPreview, MediaType.VIDEO);

    // Reconnect all remote connections
    for (UserSession remote : registry.getUsersByName().values()) {
      if (!remote.getName().equals(user.getName())) {
        WebRtcEndpoint recvEp = remote.getIncomingMedia().get(user.getName());
        if (recvEp != null) {
          senderEp.disconnect(recvEp, MediaType.VIDEO);
          filter.connect(recvEp, MediaType.VIDEO);
        }
      }
    }

    // Refresh connections
    selfPreview.gatherCandidates();
    senderEp.gatherCandidates();

    // Send confirmation
    JsonObject resp = new JsonObject();
    resp.addProperty("id", "startFilterResponse");
    session.sendMessage(new TextMessage(resp.toString()));
  }



  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    UserSession user = registry.removeBySession(session);
    roomManager.getRoom(user.getRoomName()).leave(user);
  }

  private void joinRoom(JsonObject params, WebSocketSession session) throws IOException {
    final String roomName = params.get("room").getAsString();
    final String name = params.get("name").getAsString();
    log.info("PARTICIPANT {}: trying to join room {}", name, roomName);

    Room room = roomManager.getRoom(roomName);
    final UserSession user = room.join(name, session);
    registry.register(user);
  }

  private void leaveRoom(UserSession user) throws IOException {
    final Room room = roomManager.getRoom(user.getRoomName());
    room.leave(user);
    if (room.getParticipants().isEmpty()) {
      roomManager.removeRoom(room);
    }
  }
}
