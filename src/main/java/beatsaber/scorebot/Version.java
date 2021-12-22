package beatsaber.scorebot;

import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;

@Getter
@Setter
public class Version {
    private ObjectId id;
    private int version;
    private long updateTime;
}
