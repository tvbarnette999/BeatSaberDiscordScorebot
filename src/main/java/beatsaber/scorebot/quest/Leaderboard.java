package beatsaber.scorebot.quest;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class Leaderboard {
    User user;
    int gold;
    int silver;
    int bronze;
}
