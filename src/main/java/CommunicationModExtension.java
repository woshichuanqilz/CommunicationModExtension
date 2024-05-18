import basemod.BaseMod;
import basemod.ReflectionHacks;
import basemod.interfaces.PostInitializeSubscriber;
import battleaimod.BattleAiMod;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.gikk.twirk.Twirk;
import com.gikk.twirk.TwirkBuilder;
import com.gikk.twirk.events.TwirkListener;
import com.gikk.twirk.types.twitchMessage.TwitchMessage;
import com.gikk.twirk.types.users.TwitchUser;
import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.common.DamageAllEnemiesAction;
import com.megacrit.cardcrawl.actions.common.LoseHPAction;
import com.megacrit.cardcrawl.cards.DamageInfo;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.RelicLibrary;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.vfx.ObtainKeyEffect;
import communicationmod.CommandExecutor;
import communicationmod.CommunicationMod;
import communicationmod.GameStateConverter;
import communicationmod.InvalidCommandException;
import de.robojumper.ststwitch.TwitchConfig;
import ludicrousspeed.Controller;
import twitch.QueryController;
import twitch.TwitchController;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import communicationmod.OnStateChangeSubscriber;

@SpireInitializer
public class CommunicationModExtension implements PostInitializeSubscriber {
    private class writeSocket implements OnStateChangeSubscriber{
        @Override
        public void receiveOnStateChange() {
            System.out.println("Send Socket:" + GameStateConverter.getCommunicationState());
            try {
                out.writeUTF(GameStateConverter.getCommunicationState() + "\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static CommunicationMethod communicationMethod = CommunicationMethod.SOCKET;
    private static final int PORT = 8080;
    private static DataOutputStream out;
    private static writeSocket writeSocket;


    enum CommunicationMethod {
        SOCKET,
        TWITCH_CHAT,
        EXTERNAL_PROCESS
    }

    public static final HashMap<String, String> relicNameToIdmap = new HashMap<>();

    public static void initialize() {
        BaseMod.subscribe(new CommunicationModExtension());
    }

    @SpirePatch(clz = CommunicationMod.class, method = "startExternalProcess", paramtypez = {})
    public static class NetworkCommunicationPatch {
        @SpirePrefixPatch
        public static SpireReturn startNetworkCommunications(CommunicationMod communicationMod) {
            switch (communicationMethod) {
                case SOCKET:
                    setSocketThreads();
                    return SpireReturn.Return(true);
                case TWITCH_CHAT:
                    setTwitchThreads();
                    return SpireReturn.Return(true);
                case EXTERNAL_PROCESS:
                default:
                    return SpireReturn.Continue();
            }
        }
    }

    private static void setSocketThreads() {
        final boolean[] isClose = {false};
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();


        Thread starterThread = new Thread(() -> {
            try {
                // start stuff then start read thread and write thread
                ServerSocket serverSocket = new ServerSocket(PORT);
                System.out.println("socket service start...");

                Socket client_socket = serverSocket.accept();
                out = new DataOutputStream(client_socket.getOutputStream());

                Thread writeThread = new Thread(() -> {
                    CommunicationMod.subscribe(CommunicationModExtension.writeSocket);
                });
                writeThread.start();

                Thread readThread = new Thread(() -> {
                    try {
//                        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                        BufferedReader in = new BufferedReader(new InputStreamReader(client_socket.getInputStream()));
                        while (true) {
                            try {
                                String s = in.readLine();
                                if (s == null) {
                                    // close socket and write thread and read thread
                                    client_socket.close();
                                    serverSocket.close();
                                    writeThread.interrupt();
                                    Thread.currentThread().interrupt();
                                    isClose[0] = true;
                                    break;
                                }
                                System.out.println("Recv Message: "+ s);
                                CommunicationMod.queueCommand(s);
                            } catch (EOFException eofe) {
                                System.out.println("End of file reached");
                                break;
                            } catch (IOException ioe) {
                                ioe.printStackTrace();
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                readThread.start();
                System.out.println("reading thread start socket...");
            } catch (IOException e) {
                e.printStackTrace();
            }

            // wait for close by using ScheduledExecutorService

        });

        Runnable task = () -> {
            if (isClose[0]) {
                System.out.println("Close socket service");
                executorService.shutdownNow();
                starterThread.interrupt();
                isClose[0] = true;
                setSocketThreads();
            }
        };
        executorService.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS);

        starterThread.start();
    }

    public static void setTwitchThreads() {
        Optional<TwitchConfig> twitchConfigOptional = TwitchConfig.readConfig();
        if (twitchConfigOptional.isPresent()) {
            TwitchConfig twitchConfig = twitchConfigOptional.get();

            String channel = ReflectionHacks
                    .getPrivate(twitchConfig, TwitchConfig.class, "channel");
            String username = ReflectionHacks
                    .getPrivate(twitchConfig, TwitchConfig.class, "username");
            String token = ReflectionHacks.getPrivate(twitchConfig, TwitchConfig.class, "token");

            try {
                Twirk twirk = new TwirkBuilder(channel, username, token).setSSL(true).build();

                TwitchController controller = new TwitchController(twirk);
                BaseMod.subscribe(controller);

                twirk.addIrcListener(new TwirkListener() {
                    @Override
                    public void onPrivMsg(TwitchUser sender, TwitchMessage message) {
                        try {
                            controller.receiveMessage(sender, message.getContent());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                CommunicationMod.subscribe(() -> {
                    try {
                        controller
                                .startVote(GameStateConverter.getCommunicationState());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                twirk.connect();
                System.err.println("connected as " + username);

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static class ColonelSanders implements Controller {
        private boolean shouldSend = true;

        @Override
        public void step() {
            LinkedBlockingQueue<String> writeQueue =
                    ReflectionHacks
                            .getPrivateStatic(CommunicationMod.class, "writeQueue");

            if (writeQueue != null && shouldSend) {
                shouldSend = false;
                writeQueue.add(GameStateConverter.getCommunicationState());
            }


            BlockingQueue<String> readQueue = ReflectionHacks
                    .getPrivateStatic(CommunicationMod.class, "readQueue");
            if (readQueue != null && !readQueue.isEmpty()) {
                try {
                    CommandExecutor.executeCommand(readQueue.poll());
                    shouldSend = true;
                } catch (InvalidCommandException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public boolean isDone() {
            return false;
        }
    }

    @Override
    public void receivePostInitialize() {
        if (BaseMod
                .hasModID("BattleAiMod:") && communicationMethod == CommunicationMethod.TWITCH_CHAT) {
            if (BattleAiMod.isClient) {
                setTwitchThreads();
                sendSuccessToController();
            }
        }

        QueryController.getAllRelics().forEach(relic -> relicNameToIdmap
                .put(QueryController.makeKey(relic), relic.relicId));
    }

    private static final String HOST_IP = "127.0.0.1";
    private static final int SERVER_PORT = 5123;

    private static void sendSuccessToController() {
        new Thread(() -> {
            try {
                Thread.sleep(5_000);
                Socket socket;
                socket = new Socket();
                socket.connect(new InetSocketAddress(HOST_IP, SERVER_PORT));
                new DataOutputStream(socket.getOutputStream()).writeUTF("SUCCESS");
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket
                        .getInputStream()));

                while (true) {
                    String controllerLine = in.readUTF();
                    if (controllerLine.equals("kill")) {
                        int monsterCount = AbstractDungeon.getCurrRoom().monsters.monsters.size();
                        int[] multiDamage = new int[monsterCount];

                        for (int i = 0; i < monsterCount; ++i) {
                            multiDamage[i] = 999;
                        }

                        AbstractDungeon.actionManager
                                .addToTop(new DamageAllEnemiesAction(AbstractDungeon.player, multiDamage, DamageInfo.DamageType.HP_LOSS, AbstractGameAction.AttackEffect.NONE));
                    } else if (controllerLine.equals("start")) {
                        CommunicationMod.queueCommand("start ironclad");
                    } else if (controllerLine.equals("enable")) {
                        System.out.println("received enable signal");
                        TwitchController.enable();
                    } else if (controllerLine.equals("state")) {
                        CommunicationMod.mustSendGameState = true;
                    } else if (controllerLine.equals("battlerestart")) {
                        TwitchController.battleRestart();
                    } else if (controllerLine.equals("advancegame")) {
                        CommunicationMod.mustSendGameState = true;
                        TwitchController.inBattle = false;
                    } else if (controllerLine.equals("addkeys")) {
                        if (!Settings.hasRubyKey) {
                            AbstractDungeon.topLevelEffects.add(new ObtainKeyEffect(ObtainKeyEffect.KeyColor.RED));
                        }

                        if (!Settings.hasSapphireKey) {
                            AbstractDungeon.topLevelEffects.add(new ObtainKeyEffect(ObtainKeyEffect.KeyColor.BLUE));
                        }

                        if (!Settings.hasEmeraldKey) {
                            AbstractDungeon.topLevelEffects.add(new ObtainKeyEffect(ObtainKeyEffect.KeyColor.GREEN));
                        }
                    } else if (controllerLine.equals("losebattle")) {
                        AbstractDungeon.actionManager
                                .addToTop(new LoseHPAction(AbstractDungeon.player, AbstractDungeon.player, 999));
                    } else if (controllerLine.startsWith("loserelic")) {
                        try {
                            String[] commandTokens = controllerLine.split(" ");
                            if (commandTokens.length >= 2) {
                                String relicId = relicNameToIdmap
                                        .get(QueryController.makeKey(commandTokens[1]));
                                AbstractDungeon.player.loseRelic(relicId);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (controllerLine.startsWith("addrelic")) {
                        try {
                            String[] commandTokens = controllerLine.split(" ");
                            if (commandTokens.length >= 2) {
                                String relicId = relicNameToIdmap
                                        .get(QueryController.makeKey(commandTokens[1]));

                                AbstractRelic relic = RelicLibrary
                                        .getRelic(relicId).makeCopy();

                                if (relic.img != null) {
                                    AbstractDungeon.getCurrRoom()
                                                   .spawnRelicAndObtain((float) (Settings.WIDTH / 2), (float) (Settings.HEIGHT / 2), relic);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
