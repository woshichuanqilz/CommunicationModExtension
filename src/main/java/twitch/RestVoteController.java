package twitch;

import basemod.ReflectionHacks;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;
import communicationmod.ChoiceScreenUtils;
import mintySpire.patches.map.MiniMapDisplay;

import java.util.ArrayList;
import java.util.HashMap;

import static twitch.RenderHelpers.renderTextBelowHitbox;

public class RestVoteController extends VoteController {
    private static final float SCALE = 3.3f;

    private final HashMap<String, AbstractCampfireOption> messageToRestOption;
    private final TwitchController twitchController;
    private final JsonObject stateJson;

    public static final OrthographicCamera CAMERA = new OrthographicCamera(MiniMapDisplay.FRAME_BUFFER
            .getWidth() * SCALE, MiniMapDisplay.FRAME_BUFFER
            .getHeight() * SCALE);

    RestVoteController(TwitchController twitchController, JsonObject stateJson) {
        this.twitchController = twitchController;
        messageToRestOption = new HashMap<>();

        ArrayList<AbstractCampfireOption> restOptions = ReflectionHacks
                .privateStaticMethod(ChoiceScreenUtils.class, "getValidRestRoomButtons")
                .invoke();

        for (AbstractCampfireOption restOption : restOptions) {
            messageToRestOption.put(getCampfireOptionName(restOption), restOption);
        }

        this.stateJson = stateJson;
    }

    @Override
    public void setUpChoices() {
        twitchController.setUpDefaultVoteOptions(stateJson);
    }

    @Override
    public void render(SpriteBatch spriteBatch) {
        MiniMapDisplay.renderMinimap(spriteBatch, Settings.WIDTH / 8.f, 0, CAMERA);

        HashMap<String, Integer> voteFrequencies = twitchController.getVoteFrequencies();
        for (int i = 0; i < twitchController.viableChoices.size(); i++) {
            TwitchController.Choice choice = twitchController.viableChoices.get(i);

            String message = choice.choiceName;
            if (messageToRestOption.containsKey(message)) {
                AbstractCampfireOption fireOption = messageToRestOption.get(message);
                Hitbox hitbox = fireOption.hb;
                String voteMessage = String.format("[vote %s] (%s)",
                        choice.voteString,
                        voteFrequencies.getOrDefault(choice.voteString, 0));

                renderTextBelowHitbox(spriteBatch, voteMessage, adjustSelectionHitbox(hitbox));
            } else {
                System.err.println("no boss relic button for " + choice.choiceName);
            }
        }
    }

    @Override
    public void endVote() {

    }

    private static String getCampfireOptionName(AbstractCampfireOption option) {
        String classname = option.getClass().getSimpleName();
        String nameWithoutOption = classname.substring(0, classname.length() - "Option".length());
        return nameWithoutOption.toLowerCase();
    }

    private static Hitbox adjustSelectionHitbox(Hitbox hitbox) {
        return new Hitbox(hitbox.x, hitbox.y - 62.0F * Settings.scale, hitbox.width, hitbox.height + 62.F * Settings.scale);
    }
}
