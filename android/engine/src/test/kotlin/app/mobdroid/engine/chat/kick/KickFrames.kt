package app.mobdroid.engine.chat.kick

internal object KickFrames {
    val PLAIN =
        """{"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"6f1d2c4e-0b7a-4c55-9d1e-2a8f3b7c9e10\",\"chatroom_id\":100200,\"content\":\"that last play was clean\",\"type\":\"message\",\"created_at\":\"2026-11-10T03:32:02+00:00\",\"sender\":{\"id\":4821,\"username\":\"PixelFox\",\"slug\":\"pixelfox\",\"identity\":{\"color\":\"#FEA0CF\",\"badges\":[],\"badges_v2\":[{\"name\":\"level\",\"badge_type\":\"global\",\"image_url\":\"https:\\/\\/ext.cdn.kick.com\\/chat\\/badges\\/29_36298231-50cc-41fa-b3e6-5401d216386d.png\",\"metadata\":{\"level\":29},\"selected\":true,\"sort_order\":1}]}},\"metadata\":{\"message_ref\":\"1794281522944\"}}","channel":"chatrooms.100200.v2"}"""

    val EMOTES =
        """{"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"b492840d-970b-4b3c-85f1-c9351c5a477f\",\"chatroom_id\":100200,\"content\":\"[emote:37230:POLICE] CLEAN RUN [emote:37230:POLICE]\",\"type\":\"message\",\"created_at\":\"2026-11-10T03:32:05+00:00\",\"sender\":{\"id\":90177,\"username\":\"ModMoss\",\"slug\":\"modmoss\",\"identity\":{\"color\":\"#FF9D00\",\"badges\":[{\"type\":\"moderator\",\"text\":\"Moderator\",\"sort_order\":12},{\"type\":\"subscriber\",\"text\":\"Subscriber\",\"count\":35,\"sort_order\":9}],\"badges_v2\":[{\"name\":\"level\",\"badge_type\":\"global\",\"image_url\":\"https:\\/\\/ext.cdn.kick.com\\/chat\\/badges\\/29_36298231-50cc-41fa-b3e6-5401d216386d.png\",\"metadata\":{\"level\":29},\"selected\":true,\"sort_order\":1}]}},\"metadata\":{\"message_ref\":\"1794281522944\"}}","channel":"chatrooms.100200.v2"}"""

    val BROADCASTER =
        """{"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"0a7b7c1e-52d4-4d54-a2f5-9a1d7e3b6c20\",\"chatroom_id\":100200,\"content\":\"welcome in\",\"type\":\"message\",\"created_at\":\"2026-11-10T03:32:09+00:00\",\"sender\":{\"id\":100200,\"username\":\"ChannelOwner\",\"slug\":\"channelowner\",\"identity\":{\"color\":\"#00F1FF\",\"badges\":[{\"type\":\"broadcaster\",\"text\":\"Broadcaster\",\"sort_order\":10}],\"badges_v2\":[]}},\"metadata\":{\"message_ref\":\"1794281522944\"}}","channel":"chatrooms.100200.v2"}"""

    val NO_COLOR =
        """{"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"c3d4e5f6-1a2b-4c3d-8e4f-5a6b7c8d9e01\",\"chatroom_id\":100200,\"content\":\"hello from the void\",\"type\":\"message\",\"created_at\":\"2026-11-10T03:32:07+00:00\",\"sender\":{\"id\":512,\"username\":\"quiet_user\",\"slug\":\"quiet-user\",\"identity\":{\"badges\":[]}},\"metadata\":{\"message_ref\":\"1794281522944\"}}","channel":"chatrooms.100200.v2"}"""

    val REPLY =
        """{"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"293e5b53-3dae-4920-a8b5-6f6c9fc0c9e1\",\"chatroom_id\":100200,\"content\":\"about five hours\",\"type\":\"reply\",\"created_at\":\"2026-11-10T03:33:26+00:00\",\"sender\":{\"id\":7733,\"username\":\"ReplyRaven\",\"slug\":\"replyraven\",\"identity\":{\"color\":\"#FFAE76\",\"badges\":[{\"type\":\"subscriber\",\"text\":\"Subscriber\",\"count\":1,\"sort_order\":9}],\"badges_v2\":[{\"name\":\"level\",\"badge_type\":\"global\",\"image_url\":\"https:\\/\\/ext.cdn.kick.com\\/chat\\/badges\\/29_36298231-50cc-41fa-b3e6-5401d216386d.png\",\"metadata\":{\"level\":29},\"selected\":true,\"sort_order\":1}]}},\"metadata\":{\"original_sender\":{\"id\":39788,\"username\":\"PixelFox\"},\"original_message\":{\"id\":\"41ac4b6b-79db-4b7d-a4d4-9696e9a7ca6d\",\"content\":\"how is the wow grind going\"},\"message_ref\":\"1794281522944\"},\"thread_parent_id\":\"41ac4b6b-79db-4b7d-a4d4-9696e9a7ca6d\"}","channel":"chatrooms.100200.v2"}"""

    val DELETED =
        """{"event":"App\\Events\\MessageDeletedEvent","data":"{\"id\":\"2e17fcf0-36f8-4d46-ba8e-45d1b84b0ca0\",\"message\":{\"id\":\"0c4cdb14-56e5-49fe-94da-fd68ff0cbb2c\"},\"aiModerated\":true,\"violatedRules\":[\"hate\"]}","channel":"chatrooms.100200.v2"}"""

    val BANNED =
        """{"event":"App\\Events\\UserBannedEvent","data":"{\"id\":\"da9edae4-4dbf-4fdc-be34-e5b1374df3e5\",\"user\":{\"id\":4821,\"username\":\"PixelFox\",\"slug\":\"pixelfox\"},\"banned_by\":{\"id\":0,\"username\":\"ModMoss\",\"slug\":\"modmoss\"},\"permanent\":false,\"duration\":5,\"expires_at\":\"2026-11-10T03:39:46+00:00\"}","channel":"chatrooms.100200.v2"}"""

    val ESTABLISHED =
        """{"event":"pusher:connection_established","data":"{\"socket_id\":\"1824726.1567447\",\"activity_timeout\":120}"}"""

    val SUBSCRIBED =
        """{"event":"pusher_internal:subscription_succeeded","data":"{}","channel":"chatrooms.100200.v2"}"""

    val PING =
        """{"event":"pusher:ping","data":{}}"""

    val OBJECT_DATA =
        """{"event":"App\\Events\\ChatMessageEvent","data":{"id":"6f1d2c4e-0b7a-4c55-9d1e-2a8f3b7c9e10","chatroom_id":100200,"content":"that last play was clean","type":"message","created_at":"2026-11-10T03:32:02+00:00","sender":{"id":4821,"username":"PixelFox","slug":"pixelfox","identity":{"color":"#FEA0CF","badges":[],"badges_v2":[{"name":"level","badge_type":"global","image_url":"https://ext.cdn.kick.com/chat/badges/29_36298231-50cc-41fa-b3e6-5401d216386d.png","metadata":{"level":29},"selected":true,"sort_order":1}]}},"metadata":{"message_ref":"1794281522944"}},"channel":"chatrooms.100200.v2"}"""
}
