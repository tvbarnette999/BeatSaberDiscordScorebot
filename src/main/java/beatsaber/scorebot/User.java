package beatsaber.scorebot;

import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;

@Getter
@Setter
public class User {
    ObjectId id;
    long discordId;
    String displayName;
    long scoresaberId;
    String scoresaberIdString;
    String bsaber;
    int notify;
}
