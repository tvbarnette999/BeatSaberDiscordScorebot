package beatsaber.scorebot;

import com.google.gson.*;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Spark;

import java.awt.*;
import java.net.URLDecoder;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collectors;

import static spark.Spark.after;
import static spark.Spark.staticFiles;

public class Server {
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    private static final String DiscordBotToken = Config.getProperty("discord.token");
    private static JDA discordClient;
    private static String guildId = Config.getProperty("discord.guildId");
    private static String channelId = Config.getProperty("discord.channelId");
    private static DAO dao = new DAO();
    public static final int COMMON_COUNT = 3;
    public static final int NOTIFY_NEVER = 0;
    public static final int NOTIFY_TOP_LOSS = 1;
    public static final int NOTIFY_RANK_DOWN = 2;
    public static final int NOTIFY_RANK_CHANGE = 3;
    public static final String[] NOTIFY_MESSAGE = {
            "never", "when you are knocked out of 1st", "when your rank drops", "when your rank changes"
    };

    private static Color[] colors = {
            null,
            new Color(0, 162, 121), //green
            null,
            new Color(52, 152, 219), //blue
            null,
            new Color(218, 99, 0), //orange
            null,
            new Color(255, 56, 45), //red
            null,
            new Color(102, 16, 242), //purple
    };
    private static final String[] DIFFICULTIES = {
            null, "Easy", null, "Normal", null, "Hard", null, "Expert", null, "Expert+"
    };
    private static String getDifficultyName(int diffRank) {
        if (diffRank < 0 || diffRank > DIFFICULTIES.length) {
            return null;
        }
        return DIFFICULTIES[diffRank];
    }
    private static Color getColor(int diffRank) {
        if (diffRank < 0 || diffRank > colors.length) {
            return Color.BLACK;
        }
        return colors[diffRank];
    }

    public static Level getLevel(String hash) {
        Level level = dao.getLevelByHash(hash);
        if (level != null) {
            return level;
        }
        HttpResponse<kong.unirest.JsonNode> resp = Unirest.get("https://beatsaver.com/api/maps/hash/" + hash).asJson();
        if (resp.isSuccess()) {
            JSONObject obj = resp.getBody().getObject();
            level = new Level();
            level.setHash(hash);
            level.setId(obj.getString("id"));
            level.setName(obj.getString("name"));
            level.setRanked(obj.getBoolean("ranked"));
            JSONObject meta = obj.getJSONObject("metadata");
            level.setSongName(meta.getString("songName"));
            level.setSongSubName(meta.getString("songSubName"));
            level.setSongAuthorName(meta.getString("songAuthorName"));
            level.setLevelAuthorName(meta.getString("levelAuthorName"));
            level.setTimestamp(System.currentTimeMillis());
            dao.saveLevel(level);
            return level;
        }
        return null;
    }

    /**
     * Calculate max score based on the number of blocks w/ accumulated combo multiplier
     * @param score
     */
    public static void calculateScoreMetrics(Score score) {
       int blockCount = score.goodCuts + score.badCuts + score.missed;
        int maxScore;
        if (blockCount == 0) {
            maxScore = 0;
        } else if(blockCount < 14) {
            if (blockCount == 1) {
                maxScore = 115;
            } else if (blockCount < 5) {
                maxScore = (blockCount - 1) * 230 + 115;
            } else {
                maxScore = (blockCount - 5) * 460 + 1035;
            }
        } else {
            maxScore = (blockCount - 13) * 920 + 4715;
        }
        // TODO modifiers
        score.maxScore = maxScore;

        if (score.score == score.maxScore) {
            score.accuracy = 100;
            score.accuracyRank = "SSS";
        } else if (score.maxScore == 0) {
            score.accuracy = 0;
            score.accuracyRank = "";
        } else {
            score.accuracy = 100.0 * (double)score.score /  (double)score.maxScore;
            if (score.accuracy >= 90) score.accuracyRank = "SS";
            else if (score.accuracy >= 80) score.accuracyRank =  "S";
            else if (score.accuracy >= 65) score.accuracyRank =  "A";
            else  if (score.accuracy >= 50) score.accuracyRank =  "B";
            else if (score.accuracy >= 35) score.accuracyRank =  "C";
            else if (score.accuracy >= 20) score.accuracyRank =  "D";
            else score.accuracyRank =  "E";
        }

    }

    public static String getSongLeaderboardEmbedField(List<Score> data) {
        StringBuilder builder = new StringBuilder();
        builder.append("```");
        for (int i = 0; i < data.size(); i++) {
            Score s = data.get(i);
            // TODO ties?
            builder.append(i + 1).append(". ").append(s.score).append(" ").append(dao.getUserByDiscordId(s.getUserDiscordId()).displayName).append('\n');
        }
        builder.append("```");
        return builder.toString();
    }

    public static void scoreSubmission(ScoreSubmission score) {
        if (score.userId.equals("12345")) {
            LOG.warn("Ignoring score for default userId: {} on {}[{}]", score.score, score.songName, score.difficulty);
            return;
        }
        LOG.info("Score for {} ({}): {}", score.levelId, score.songName, score.score);
        LOG.info("Full score: " + new Gson().toJson(score));
        User user = dao.getUserByDiscordId(Long.parseLong(score.userId));
        if (user == null) {
            LOG.warn("Score from unknown userId: {}", score.userId);
            return;
        }
        final String PREFIX = "custom_level_";
        String hash1 = score.levelId.startsWith(PREFIX) ? score.levelId.substring(PREFIX.length()) : null;
        if (hash1 != null && hash1.contains("_")) {
            hash1 = hash1.substring(0, hash1.indexOf("_"));
        }
        String hash = hash1;
        if (hash == null) {
            LOG.warn("Unhashed song! {}", score.levelId);
            return;
        }
        Level level = getLevel(hash);
        if (level == null) {
            LOG.error("Failed to fetch level data for hash {}", hash);
            return;
        }
        Score previous = dao.getScore(hash, score.difficultyRank, user.discordId);
        // TODO do we want to count positive modifiers? Scoresaber currently does not.
        if (previous != null && previous.score >= Math.min(score.score, score.modifiedScore)) {
            LOG.info("Score is not player high: {} on {} by {}", score.score, level.getName(), user.getDisplayName());
            return;
        }
        if (previous == null) {
            previous = new Score();
            previous.setSongHash(hash);
            previous.setUserDiscordId(user.getDiscordId());
            previous.setDifficulty(score.difficultyRank);
        }
        previous.rawScore = score.score;
        previous.modifiedScore = score.modifiedScore;
        previous.score = Math.min(previous.rawScore, previous.modifiedScore);
        previous.goodCuts = score.goodCuts;
        previous.badCuts = score.badCuts;
        previous.missed = score.missed;
        previous.maxCombo = score.maxCombo;
        previous.fullCombo = score.fullCombo;
        previous.ok = score.ok;
        previous.notGood = score.notGood;
        previous.timestamp = System.currentTimeMillis();
        calculateScoreMetrics(previous);
        List<Score> oldLeaderboard = dao.getLeaderboard(previous.songHash, previous.difficulty);
        dao.saveScore(previous);
        List<Score> leaderboard = dao.getLeaderboard(previous.songHash, previous.difficulty);
        ToIntBiFunction<List<Score>, Long> getRank = (lb, discordId) -> {
            for (int i = 0; i < lb.size(); i++) {
                if (lb.get(i).userDiscordId == user.discordId) {
                    return i + 1;
                }
            }
            return Integer.MAX_VALUE;
        };

        /*
        Scenarios:
        1. Player sets global first score on song
        2. Player sets their first score & is bottom
        3. Player sets their first score & passes someone
        4. Top player beats themselves
        5. Non-top player beats themselves & noone else
        6. Non-top player beats themselves & passes players
         */
        /*
        Embed is not a ping. But the content row above the embed can be. Each person could config when they want to be pinged:
        0. Never
        1. If you lose top spot
        2. If your rank goes down
        3. If your rank goes up or down (excluding first play where score is bottom)
         */
        String content = null;
        try {
            StringBuilder contentBuilder = new StringBuilder();
            int oldRank = getRank.applyAsInt(oldLeaderboard, user.discordId);
            int newRank = getRank.applyAsInt(leaderboard, user.discordId);
            if (newRank < oldRank) {
                List<Long> passed = new ArrayList<>();
                for (int i = newRank; i < oldRank && i < leaderboard.size(); i++) {
                    passed.add(leaderboard.get(i).userDiscordId);
                }
                // filter the list of users passed to only include those that want to be notified in this scenario (> TOP_LOSS is RANK_DOWN && RANK_CHANGE)
                String mentions = passed.stream().map(dao::getUserByDiscordId).filter(x -> x.notify > NOTIFY_TOP_LOSS || (x.notify == NOTIFY_TOP_LOSS && leaderboard.get(1).userDiscordId == x.discordId))
                        .map(x -> "<@" + x.discordId + ">").collect(Collectors.joining(","));
                boolean selfNotify = user.notify == NOTIFY_RANK_CHANGE;
                if (mentions.isEmpty()) {
                    if (selfNotify) {
                        contentBuilder.append("<@").append(user.discordId).append("> placed into rank ").append(newRank).append("!");
                        content = contentBuilder.toString();
                    }
                } else {
                    contentBuilder.append("Hey ").append(mentions).append("! ");
                    if (selfNotify) {
                        contentBuilder.append("<@").append(user.discordId).append("> passed you! ");
                    } else {
                        contentBuilder.append(user.displayName).append(" passed you! ");
                    }
                    contentBuilder.append(Messages.MESSAGES[new Random().nextInt(Messages.MESSAGES.length)]);
                    content = contentBuilder.toString();
                }
            }
        } catch (Exception e) {
            LOG.error("Error forming embed content", e);
        }

        String acc = new DecimalFormat("0.00").format(previous.accuracy);
        String accRank = previous.accuracyRank;
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(level.getSongName() + " [" + getDifficultyName(score.difficultyRank) + "]");
        embed.setUrl("https://bsaber.com/songs/" + level.getId() + "/");
        embed.setDescription("<@"+ user.discordId + "> set a new high score of " + score.score);
        embed.setColor(getColor(score.difficultyRank));
        embed.setThumbnail("https://scoresaber.com/imports/images/songs/" + hash + ".png");
        embed.addField("Accuracy", acc + "% **" + accRank + "**", true);
        embed.addField("Combo", score.fullCombo ? "**FC** :white_check_mark:" : (score.maxCombo + " / " + score.missed + " / " + score.badCuts), true);
        embed.addField("Leaderboard:", getSongLeaderboardEmbedField(leaderboard), false);
//        MessageEmbed msgEmbed = embed.build();
//        MessageCreateSpec.Builder msg = MessageCreateSpec.builder();
//        if (content != null) {
//            msg.content(content);
//        }
//        msg.addEmbed(embed);
        TextChannel channel = discordClient.getChannelById(TextChannel.class, channelId);
        if (channel != null) {
            channel.sendMessageEmbeds(embed.build()).queue();
        }
//        discordClient.getChannelById(Snowflake.of(channelId)).ofType(MessageChannel.class)
//                .flatMap(channel -> channel.createMessage(msg.build()))//.withContent("<@"+ user.discordId + ">"))
//                .retry(5).block();

    }

    public static void main(String[] args) throws InterruptedException {
        staticFiles.location("/public");
        Spark.port(8081);
        Spark.get("/hi", (request, response) -> "Hi");
        Spark.post("/score", (request, response) -> {
            ScoreSubmission score = new Gson().fromJson(URLDecoder.decode(request.body(), "UTF-8"), ScoreSubmission.class);
            scoreSubmission(score);
            return "OK";
        });
        Spark.get("/score/submit", (request, response) -> {
            ScoreSubmission score = new Gson().fromJson(request.queryParams("data"), ScoreSubmission.class);
            scoreSubmission(score);
            return "OK";
        });
        Spark.get("/users", (request, response) -> {
            response.header("Content-Type", "application/json");
            List<User> users = dao.getAllUsers();
            users.forEach(x -> x.scoresaberIdString = Long.toString(x.scoresaberId));
            return new Gson().toJson(users);
        });
        Spark.get("/scores", (request, response) -> {
           response.header("Content-Type", "application/json");
           long since = 0;
           try {
               since = Long.parseLong(request.headers("X-Modified-Since"));
           } catch(Exception e) {
               // 0
           }
           GsonBuilder builder = new GsonBuilder();
           builder.addSerializationExclusionStrategy(new ExclusionStrategy() {

               @Override
               public boolean shouldSkipField(FieldAttributes f) {
                   for (String s : new String[]{"songHash", "difficulty", "userDiscordId", "score", "fullCombo", "accuracy", "accuracyRank"}) {
                       if (s.equals(f.getName())) {
                           return false;
                       }
                   }
                   return true;
               }

               @Override
               public boolean shouldSkipClass(Class<?> clazz) {
                   return false;
               }
           });
           return builder.create().toJson(dao.getAllScores(since));
        });
        Spark.get("/levels", (request, response) -> {
            response.header("Content-Type", "application/json");
            long since = 0;
            try {
                since = Long.parseLong(request.headers("X-Modified-Since"));
            } catch (Exception e) {
                // 0
            }
            return new Gson().toJson(dao.getAllLevels(since));
        });
        after((request, response) -> {
            response.header("Content-Encoding", "gzip");
        });


        discordClient = JDABuilder.createDefault(DiscordBotToken).enableIntents(GatewayIntent.MESSAGE_CONTENT).build().awaitReady();
        discordClient.addEventListener((net.dv8tion.jda.api.hooks.EventListener) (genericEvent -> {
            // TODO bump to 17 for named instanceof?
            if(!(genericEvent instanceof SlashCommandInteractionEvent)) {
                return;
            }
            SlashCommandInteractionEvent event = (SlashCommandInteractionEvent) genericEvent;
            OptionMapping opt;
            net.dv8tion.jda.api.entities.User discordUser;
            User user;
            EmbedBuilder embedBuilder = new EmbedBuilder();
            switch(event.getName()) {
                case "signup":
                    discordUser = event.getInteraction().getUser();
                    user = dao.getUserByDiscordId(discordUser.getIdLong());
                    if (user == null) {
                        user = new User();
                        user.setId(new ObjectId());
                    }
                    user.setDisplayName(discordUser.getEffectiveName()); // maybe this is the right name?
                    user.setDiscordId(discordUser.getIdLong());
                    dao.saveUser(user);
                    LOG.info("Mapped {} to {}", discordUser.getIdLong(), discordUser.getEffectiveName());
                    event.reply("<@" + discordUser.getIdLong() + "> added!").queue();
                    break;
                case "config":
                    opt = event.getOption("platform");
                    if (opt == null) {
                        event.reply("Must specify platform: PC or Quest").queue();
                        break;
                    }
                    discordUser = event.getInteraction().getUser();
                    String url = Config.getProperty("server.url") + "/score";
                    JsonObject obj = new JsonObject();
                    obj.addProperty("userId", discordUser.getId());
                    obj.addProperty("url", url);
                    JsonObject root = new JsonObject();
                    root.add("submitData", obj);
                    String platform = opt.getAsString();
                    String fileName = platform.equals("PC") ? "PCDiscordScorebot.json" : "quest-discord-scorebot.json";
                    String fileLocation = platform.equals("PC") ? "steamapps/common/Beat Saber/UserData/" : "ModData/com.beatgames.beatsaber/Configs/";
                    GsonBuilder gb = new GsonBuilder();
                    gb.setPrettyPrinting();
                    // TODO see if it works on both platforms - may need to append a \n for unix
//                    event.getChannel().sendFiles(FileUpload.fromData(gb.create().toJson(root).getBytes(), fileName))
//                            .addContent("<@" + discordUser.getIdLong() + ">: put this file at `" + fileLocation + "`").queue();
                    event.replyFiles(FileUpload.fromData(gb.create().toJson(root).getBytes(), fileName))
                            .addContent("<@" + discordUser.getIdLong() + ">: put this file at `" + fileLocation + "`").queue();
                    break;


//                    List<Tuple2<String, InputStream>> files = Collections.singletonList(Tuples.of(fileName, new ByteArrayInputStream(gb.create().toJson(root).getBytes())));
//                    discordClient.rest().getChannelById(event.getInteraction().getChannelId())
//                            .createMessage(MultipartRequest.ofRequestAndFiles(MessageCreateRequest.builder().content("<@" + discordUser.getId().asLong() + ">: put this file at `" + fileLocation + "`").build(), files))
//                            .retry(5).subscribe();
//                    return event.reply();
                case "notify":
                    opt = event.getOption("level");
                    if (opt == null) {
                        event.reply("Must specify notification setting: 0-3").queue();
                        break;
                    }
                    discordUser = event.getInteraction().getUser();
                    user = dao.getUserByDiscordId(discordUser.getIdLong());
                    if (user == null) {
                        event.reply("No connected user! Use `/signup` first.").queue();
                        break;
                    }
                    user.setNotify(opt.getAsInt());
                    dao.saveUser(user);
                    event.reply("You will be mentioned " + NOTIFY_MESSAGE[user.getNotify()]).queue();
                    break;
                case "install" :
                    opt = event.getOption("platform");
                    if (opt == null) {
                        event.reply("Must specify platform: PC or Quest").queue();
                        break;
                    }
                    event.reply(opt.getAsString().equals("PC") ? "https://github.com/tvbarnette999/PCDiscordScorebot#readme" : "https://github.com/tvbarnette999/QuestDiscordScorebot#readme").queue();
                    break;
                case "scoresaber":
                    opt = event.getOption("id");
                    if (opt != null) {
                        if (opt.getType() == OptionType.INTEGER) {
                            long ssUserId = opt.getAsLong();
                            discordUser = event.getInteraction().getUser();
                            user = dao.getUserByDiscordId(discordUser.getIdLong());
                            if (user == null) {
                                event.reply("No connected user! Use `/signup` first.").queue();
                                break;
                            }
                            user.setScoresaberId(ssUserId);
                            dao.saveUser(user);
                            event.reply("<@" + discordUser.getIdLong() + "> mapped to scoresaber user https://scoresaber.com/u/" + user.scoresaberId).queue();
                            break;
                        }  else {
                            event.reply("Scoresaber id must be an integer").queue();
                            break;
                        }
                    } else {
                        event.reply("Must pass a scoresaber id").queue();
                        break;
                    }
                case "bsaber":
                    opt = event.getOption("id");
                    if (opt != null) {
                        if (opt.getType() == OptionType.STRING) {
                            String bsaberId = opt.getAsString();
                            discordUser = event.getInteraction().getUser();
                            user = dao.getUserByDiscordId(discordUser.getIdLong());
                            if (user == null) {
                                event.reply("No connected user! Use `/signup` first.").queue();
                                break;
                            }
                            user.setBsaber(bsaberId);
                            dao.saveUser(user);
                            event.reply("<@" + discordUser.getIdLong() + "> mapped to bsaber user https://bsaber.com/members/" + user.bsaber + "/").queue();
                            break;
                        }  else {
                            event.reply("bsaber id must be a valid string").queue();
                            break;
                        }
                    } else {
                        event.reply("Must pass a bsaber id").queue();
                        break;
                    }
                case "common":
                    // return a randomized of songs for which all passed users have posted scores. param: n the number
                    opt = event.getOption("count");

                    List<Long> ids = new ArrayList<>();
                    ids.add(event.getInteraction().getUser().getIdLong());
                    for (int i = 0; i < COMMON_COUNT; i++) {
                        OptionMapping acc = event.getOption("account" + (i+1));
                        if (acc != null && acc.getType() == OptionType.USER) {
                            try {
                                ids.add(acc.getAsUser().getIdLong());
                            } catch(Exception e) {
                                LOG.error("Error getting /common command users", e);
                            }
                        }
                    }
                    StringBuilder builder = new StringBuilder();
                    builder.append("```");
                    int limit = opt != null && opt.getType() == OptionType.INTEGER ? opt.getAsInt() : 100;
                    dao.getCommonSongs(limit, ids.stream().mapToLong(Long::longValue).toArray()).forEach(x -> {
                        if (builder.length() + x.songName.length() + 5 < 2000) {
                            builder.append("* ").append(x.songName).append("\n");
                        }
                    });
                    builder.append("```");
                    event.reply(builder.toString()).queue();
                    break;
                case "leaderboard":
                    // return a leaderboard of how many 1st, 2nd, and 3rd places everyone has
                    List<Leaderboard> lbs = dao.getLeaderboard();
                    StringBuilder sb = new StringBuilder();
                    sb.append("[Click here for detailed leaderboard](").append(Config.getProperty("server.url")).append(")\n");
                    sb.append("```");
                    sb.append("Rnk  1st  2nd  3rd  Name\n");
                    for (int i = 0; i < lbs.size(); i++) {
                        // TODO zfill
                        int g = lbs.get(i).gold;
                        int s = lbs.get(i).silver;
                        int b = lbs.get(i).bronze;
                        sb.append(" ").append(i + 1).append(".  ").append(g < 10 ? " " : "")
                                .append(g).append(g < 100 ? " " : "").append("  ").append(s < 10 ? " " : "")
                                .append(s).append(s < 100 ? " " : "").append("  ").append(b < 10 ? " " : "")
                                .append(b).append(b < 100 ? " " : "").append("  ")
                                .append(lbs.get(i).user.displayName).append("\n");
                    }
                    sb.append("```");
                    embedBuilder.setTitle("Leaderboard");
                    embedBuilder.setDescription(sb.toString());
                    event.replyEmbeds(embedBuilder.build()).queue();
//                    event.reply(InteractionApplicationCommandCallbackSpec.builder().addEmbed(EmbedCreateSpec.builder()
//                            .title("Leaderboard")
//                            .description(sb.toString())
//                            .build()).build());
                    break;
                case "ping":
                    event.reply("Pong!").queue();
                    break;
                case "song":
                    opt = event.getOption("name");
                    if (opt == null) {
                        event.reply("Must include a song name!").queue();
                        return;
                    }
                    String id = opt.getAsString();
                    if (id.contains(" - ")) {
                        id = id.substring(0, id.indexOf(" - "));
                    }
                    Level level = dao.getLevelById(id);
                    if (level == null) {
                        event.reply("Song not found").queue();
                        break;
                    }
                    int diff = dao.getHighestDifficulty(level.hash);

                    embedBuilder.setAuthor(level.songAuthorName, null, null);
                    embedBuilder.setTitle(level.songName);
                    embedBuilder.setThumbnail("https://scoresaber.com/imports/images/songs/" + level.hash + ".png");
                    embedBuilder.setUrl("https://bsaber.com/songs/" + level.getId() + "/");
                    embedBuilder.setDescription("Mapped by: " + level.levelAuthorName + "\nRanked: " + (level.ranked ? "Yes" : "No") +
                                    "\n[BSR](https://beatsaver.com/maps/" + level.id+") " +
                                    "\n[Preview](https://skystudioapps.com/bs-viewer/?id=" + level.id + ")");
                    if (diff > 0) {
                        List<Score> scores = dao.getLeaderboard(level.hash, diff);
                        embedBuilder.addField("Leaderboard [" + getDifficultyName(diff) + "]", getSongLeaderboardEmbedField(scores), false);
                    }
                    event.replyEmbeds(embedBuilder.build()).queue();
                    break;
                default:
                    event.reply("Command name '" + event.getName() + "' not supported").queue();
            }
        }));

        discordClient.addEventListener((net.dv8tion.jda.api.hooks.EventListener) (genericEvent -> {
            if(!(genericEvent instanceof CommandAutoCompleteInteractionEvent)) {
                return;
            }
            CommandAutoCompleteInteractionEvent event = (CommandAutoCompleteInteractionEvent) genericEvent;
            if (event.getName().equals("song") && event.getFocusedOption().getName().equals("name")) {
                String prefix = event.getFocusedOption().getValue();
                List<Level> levels = dao.getLevelsByName(prefix);
                event.replyChoices(levels.stream().map(c -> new Command.Choice(c.id + " - " + c.songName, c.id)).collect(Collectors.toList())).queue();
            }
        }));

//        Snowflake applicationId = discordClient.getApplicationInfo().retry(5).block().getId();
//        Snowflake guildSnowflake = Snowflake.of(guildId);
//        discordClient.getRestClient().getApplicationService().bulkOverwriteGuildApplicationCommand(applicationId.asLong(), guildSnowflake.asLong(), getCommandRequests()).retry(5).subscribe();
        discordClient.updateCommands().addCommands(getCommandRequests()).queue();
        // TODO (above i old, new is: should be channel.getGuild().updateCommands().addCommands(getCommandData()).queue();
    }

    /*
        Generate the command suggestions
     */
    private static List<CommandData> getCommandRequests() {
        List<CommandData> list = new ArrayList<>();
        list.add(Commands.slash("signup", "Sign up to have your scores submitted"));
        OptionData platforms =  new OptionData(OptionType.STRING, "platform", "The platform BeatSaber is running on.", true)
                        .addChoice("PC", "PC")
                        .addChoice("Quest", "QUEST");
        list.add(Commands.slash("config", "Get customized config file for the mod").addOptions(platforms));
        list.add(Commands.slash("install", "Get install instructions").addOptions(platforms));
        list.add(Commands.slash("notify", "Set when the bot mentions you in a score post.").addOptions(
                new OptionData(OptionType.INTEGER, "level", "The mention setting", true)
                        .addChoice(NOTIFY_MESSAGE[0], 0)
                        .addChoice(NOTIFY_MESSAGE[1], 1)
                        .addChoice(NOTIFY_MESSAGE[2], 2)
                        .addChoice(NOTIFY_MESSAGE[3], 3)
        ));
        list.add(Commands.slash("scoresaber", "Associate your account with a scoresaber account").addOptions(
                new OptionData(OptionType.INTEGER, "id", "Scoresaber user ID", true)
        ));
        list.add(Commands.slash("bsaber", "Associate your account with a bsaber account").addOptions(
                new OptionData(OptionType.STRING, "id", "bsaber user ID", true)
        ));
        SlashCommandData common = Commands.slash("common", "Get list of songs you and others both have scores").addOptions(
                new OptionData(OptionType.INTEGER, "count", "The number of songs to list", true)
        );
        for (int i = 0; i < COMMON_COUNT; i++) {
            common.addOptions(new OptionData(OptionType.USER, "account" + (i+1), "An account to intersect", i == 0));
        }
        list.add(common);
        list.add(Commands.slash("song", "Info about a song").addOptions(
                new OptionData(OptionType.STRING, "name", "Song name", true, true)
        ));
        list.add(Commands.slash("leaderboard", "Get overall leaderboard"));
        list.add(Commands.slash("ping", "Ping the bot"));
        return list;
    }
}
