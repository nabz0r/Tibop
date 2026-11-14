package fr.lettrine.game;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class ShopPanel {
    private record ThemeOffer(int index, TextView price, TextView action) {}

    private final ViewKit ui;
    private final BonusEconomy economy;
    private final int currentTheme;
    private final BooleanSupplier adAvailable;
    private final LinearLayout panel;
    private final TextView coins;
    private final TextView tickets;
    private final TextView exchange;
    private final TextView video;
    private final List<ThemeOffer> themes = new ArrayList<>();

    ShopPanel(ViewKit ui, BonusEconomy economy, int currentTheme, BooleanSupplier adAvailable,
              Consumer<LocalDate> onWatchVideo, Runnable onExchange, IntConsumer onChooseTheme,
              Runnable onOpenCollection) {
        this.ui = ui;
        this.economy = economy;
        this.currentTheme = currentTheme;
        this.adAvailable = adAvailable;
        panel = ui.column();

        LinearLayout wallet = ui.column();
        wallet.addView(ui.title(label(R.string.shop_title), 28));
        ui.gap(wallet, 7);
        coins = ui.title("", 21);
        coins.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        wallet.addView(coins);
        tickets = ui.body("");
        wallet.addView(tickets);
        panel.addView(ui.illustratedCard(R.drawable.nab_arcade_shop, 104, wallet, ui.palette.ordinary()));
        ui.gap(panel, 12);

        LinearLayout ticketCard = ui.card();
        ticketCard.addView(ui.title(label(R.string.shop_tickets), 21));
        ui.gap(ticketCard, 4);
        ticketCard.addView(ui.body(label(R.string.shop_tickets_detail)));
        ui.gap(ticketCard, 9);
        exchange = ui.button(label(R.string.bonus_exchange, BonusEconomy.EXCHANGE_COST), true, onExchange);
        ticketCard.addView(exchange);
        ui.gap(ticketCard, 7);
        video = ui.button("", false, () -> onWatchVideo.accept(LocalDate.now()));
        ticketCard.addView(video);
        panel.addView(ticketCard);
        ui.gap(panel, 14);

        panel.addView(ui.title(label(R.string.shop_themes), 21));
        for (int i = 0; i < Palette.THEMES.length; i++) {
            ui.gap(panel, 8);
            panel.addView(themeCard(i, onChooseTheme));
        }
        ui.gap(panel, 12);
        panel.addView(ui.option("◇", label(R.string.skins_title), label(R.string.collection_profile_detail),
                onOpenCollection));
        refresh();
    }

    View view() { return panel; }

    void refresh() {
        BonusEconomy.Wallet wallet = economy.wallet(System.currentTimeMillis());
        coins.setText(ui.context.getResources().getQuantityString(R.plurals.shop_coins, wallet.coins(), wallet.coins()));
        tickets.setText(label(R.string.shop_ticket_balance, wallet.freeTickets(), BonusEconomy.FREE_CAP,
                wallet.bonusTickets()));
        ui.enabled(exchange, wallet.coins() >= BonusEconomy.EXCHANGE_COST && wallet.bonusTickets() < Integer.MAX_VALUE);

        boolean supported = adAvailable.getAsBoolean();
        boolean availableToday = economy.videoAvailable(LocalDate.now());
        video.setText(!supported ? R.string.bonus_video_unavailable
                : availableToday ? R.string.bonus_watch_video : R.string.bonus_video_claimed);
        ui.enabled(video, supported && availableToday);

        for (ThemeOffer offer : themes) {
            String id = Palette.unlockId(offer.index());
            int cost = BonusEconomy.themeCost(id);
            boolean free = id.isEmpty();
            boolean unlocked = free || economy.themeUnlocked(id);
            boolean equipped = offer.index() == currentTheme;
            offer.price().setText(free ? label(R.string.shop_included)
                    : unlocked ? label(R.string.shop_unlocked) : label(R.string.bonus_theme_cost, cost));
            offer.action().setText(equipped ? R.string.skins_active
                    : unlocked ? R.string.skins_equip : R.string.bonus_theme_unlock);
            String theme = label(Palette.THEMES[offer.index()].name());
            offer.action().setContentDescription(label(equipped ? R.string.skins_equipped_notice
                    : unlocked ? R.string.skins_equip_named : R.string.shop_unlock_named, theme));
            ui.enabled(offer.action(), !equipped && (unlocked || wallet.coins() >= cost));
        }
    }

    private View themeCard(int index, IntConsumer onChooseTheme) {
        Palette palette = Palette.THEMES[index];
        LinearLayout card = ui.card();
        LinearLayout info = ui.row();
        LinearLayout details = ui.column();
        details.addView(ui.title(label(palette.name()), 18));
        ui.gap(details, 3);
        TextView price = ui.body("");
        details.addView(price);
        ui.weight(info, details);
        card.addView(info);

        LinearLayout swatches = ui.row();
        swatches.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ui.inset(swatches, 4, 4);
        swatches.setBackground(ui.shape(palette.deep(), 9));
        int[] colors = {palette.tile(), palette.doubleLetter(), palette.doubleWord()};
        for (int i = 0; i < colors.length; i++) {
            if (i > 0) ui.space(swatches, 3);
            View cell = new View(ui.context);
            cell.setBackground(ui.shape(colors[i], 4));
            swatches.addView(cell, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        }
        ui.space(info, 5);
        info.addView(swatches);

        TextView action = ui.button("", false, () -> onChooseTheme.accept(index));
        var configuration = ui.context.getResources().getConfiguration();
        if (Palette.unlockId(index).isEmpty()
                && configuration.screenWidthDp >= 340 && configuration.fontScale <= 1.15f) {
            ui.space(info, 7);
            info.addView(action, new LinearLayout.LayoutParams(-2, -2));
        } else {
            ui.gap(card, 8);
            card.addView(action);
        }
        themes.add(new ThemeOffer(index, price, action));
        return card;
    }

    private String label(int resource, Object... parameters) {
        return ui.context.getString(resource, parameters);
    }
}
