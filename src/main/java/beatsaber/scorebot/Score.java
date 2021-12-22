package beatsaber.scorebot;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Score {
    String songHash;
    int difficulty;
    long userDiscordId;
    int score;
    int rawScore;
    int modifiedScore;
    boolean fullCombo;
    long timestamp;
    int goodCuts;
    int badCuts;
    int missed;
    int notGood;
    int ok;
    int maxCombo;
    int maxScore;
    double accuracy;
    String accuracyRank;
}
