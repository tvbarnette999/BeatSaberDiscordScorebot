package beatsaber.scorebot.quest;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Level {
    String id;
    String hash;
    String name;
    String songName;
    String songSubName;
    String songAuthorName;
    String levelAuthorName;
    boolean ranked;
    long timestamp;
}
