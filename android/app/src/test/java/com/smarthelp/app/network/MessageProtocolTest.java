package com.smarthelp.app.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageProtocolTest {

    @Test
    public void createScreenshotMessage_includesQueryAndVerifyFlag() throws Exception {
        JsonObject json = JsonParser.parseString(
                MessageProtocol.createScreenshotMessage("abc123", "open camera", true, "zh")).getAsJsonObject();

        assertEquals("image", json.get("type").getAsString());
        assertEquals("abc123", json.get("data").getAsString());
        assertEquals("open camera", json.get("query").getAsString());
        assertEquals("zh", json.get("language").getAsString());
        assertTrue(json.get("verify").getAsBoolean());
    }

    @Test
    public void createTextMessage_includesLanguageWhenProvided() throws Exception {
        JsonObject json = JsonParser.parseString(
                MessageProtocol.createTextMessage("open camera", "en")).getAsJsonObject();

        assertEquals("text", json.get("type").getAsString());
        assertEquals("open camera", json.get("text").getAsString());
        assertEquals("en", json.get("language").getAsString());
    }

    @Test
    public void decodeServerMessage_parsesHighlightPayload() throws Exception {
        MessageProtocol.ServerMessage message = MessageProtocol.decodeServerMessage(
                "{\"type\":\"highlight\",\"x\":42,\"y\":84,\"completed\":true}");

        assertEquals(MessageProtocol.ServerMessage.Type.HIGHLIGHT, message.getType());
        assertEquals(42, message.getHighlightX());
        assertEquals(84, message.getHighlightY());
        assertTrue(message.isCompleted());
    }

    @Test
    public void decodeServerMessage_roundsFractionalHighlightCoordinates() throws Exception {
        MessageProtocol.ServerMessage message = MessageProtocol.decodeServerMessage(
                "{\"type\":\"highlight\",\"x\":42.6,\"y\":84.2,\"completed\":false}");

        assertEquals(MessageProtocol.ServerMessage.Type.HIGHLIGHT, message.getType());
        assertEquals(43, message.getHighlightX());
        assertEquals(84, message.getHighlightY());
    }

    @Test
    public void decodeServerMessage_parsesHighlightBoundsAndTarget() throws Exception {
        MessageProtocol.ServerMessage message = MessageProtocol.decodeServerMessage(
                "{\"type\":\"highlight\",\"x\":30,\"y\":80,\"x1\":20,\"y1\":70,\"x2\":40,\"y2\":90,"
                        + "\"target\":\"Phone\",\"matchHints\":[\"green phone\",\"handset\"],\"completed\":false}");

        assertEquals(MessageProtocol.ServerMessage.Type.HIGHLIGHT, message.getType());
        assertTrue(message.hasHighlightBounds());
        assertEquals(20, message.getHighlightX1());
        assertEquals(70, message.getHighlightY1());
        assertEquals(40, message.getHighlightX2());
        assertEquals(90, message.getHighlightY2());
        assertEquals("Phone", message.getTarget());
        assertEquals(2, message.getMatchHints().size());
        assertEquals("green phone", message.getMatchHints().get(0));
    }

    @Test
    public void decodeServerMessage_parsesTaskComplete() throws Exception {
        MessageProtocol.ServerMessage message = MessageProtocol.decodeServerMessage(
                "{\"type\":\"taskComplete\",\"goal\":\"Open WhatsApp\"}");

        assertEquals(MessageProtocol.ServerMessage.Type.TASK_COMPLETE, message.getType());
        assertEquals("Open WhatsApp", message.getGoal());
    }

    @Test
    public void decodeServerMessage_parsesVoiceDisplayFlags() throws Exception {
        MessageProtocol.ServerMessage message = MessageProtocol.decodeServerMessage(
                "{\"type\":\"text\",\"text\":\"Tap Settings\",\"speak\":false,\"display\":true}");

        assertEquals(MessageProtocol.ServerMessage.Type.TEXT, message.getType());
        assertEquals("Tap Settings", message.getText());
        assertFalse(message.shouldSpeak());
        assertTrue(message.shouldDisplay());
    }

    @Test
    public void decodeServerMessage_parsesAudioText() throws Exception {
        MessageProtocol.ServerMessage message = MessageProtocol.decodeServerMessage(
                "{\"type\":\"audio\",\"data\":\"abc123\",\"text\":\"Tap Settings\"}");

        assertEquals(MessageProtocol.ServerMessage.Type.AUDIO, message.getType());
        assertEquals("abc123", message.getAudioData());
        assertEquals("Tap Settings", message.getAudioText());
    }
}
