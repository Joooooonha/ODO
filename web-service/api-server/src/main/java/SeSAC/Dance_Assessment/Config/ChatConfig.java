package SeSAC.Dance_Assessment.Config;

import SeSAC.Dance_Assessment.Service.Chat.ChatSendProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 채팅 설정 값({@code chat.*})을 등록한다. */
@Configuration
@EnableConfigurationProperties(ChatSendProperties.class)
public class ChatConfig {
}
