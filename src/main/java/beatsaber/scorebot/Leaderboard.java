package beatsaber.scorebot;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Leaderboard {
    User user;
    int gold;
    int silver;
    int bronze;
}
