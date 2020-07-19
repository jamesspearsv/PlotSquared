/*
 *       _____  _       _    _____                                _
 *      |  __ \| |     | |  / ____|                              | |
 *      | |__) | | ___ | |_| (___   __ _ _   _  __ _ _ __ ___  __| |
 *      |  ___/| |/ _ \| __|\___ \ / _` | | | |/ _` | '__/ _ \/ _` |
 *      | |    | | (_) | |_ ____) | (_| | |_| | (_| | | |  __/ (_| |
 *      |_|    |_|\___/ \__|_____/ \__, |\__,_|\__,_|_|  \___|\__,_|
 *                                    | |
 *                                    |_|
 *            PlotSquared plot management system for Minecraft
 *                  Copyright (C) 2020 IntellectualSites
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.plotsquared.core.command;

import com.google.inject.Inject;
import com.plotsquared.core.PlotSquared;
import com.plotsquared.core.configuration.Captions;
import com.plotsquared.core.configuration.Settings;
import com.plotsquared.core.configuration.caption.Templates;
import com.plotsquared.core.configuration.caption.TranslatableCaption;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotArea;
import com.plotsquared.core.plot.expiration.ExpireManager;
import com.plotsquared.core.plot.flag.implementations.DoneFlag;
import com.plotsquared.core.plot.flag.implementations.PriceFlag;
import com.plotsquared.core.plot.world.PlotAreaManager;
import com.plotsquared.core.util.EconHandler;
import com.plotsquared.core.util.MainUtil;
import com.plotsquared.core.util.MathMan;
import com.plotsquared.core.util.Permissions;
import com.plotsquared.core.util.PlayerManager;
import com.plotsquared.core.util.StringComparison;
import com.plotsquared.core.util.StringMan;
import com.plotsquared.core.util.TabCompletions;
import com.plotsquared.core.util.query.PlotQuery;
import com.plotsquared.core.util.query.SortingStrategy;
import com.plotsquared.core.uuid.UUIDMapping;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@CommandDeclaration(command = "list",
    aliases = {"l", "find", "search"},
    description = "List plots",
    permission = "plots.list",
    category = CommandCategory.INFO,
    usage = "/plot list <forsale|mine|shared|world|top|all|unowned|player|world|done|fuzzy <search...>> [#]")
public class ListCmd extends SubCommand {

    private final PlotAreaManager plotAreaManager;
    private final EconHandler econHandler;

    @Inject public ListCmd(@Nonnull final PlotAreaManager plotAreaManager,
                           @Nullable final EconHandler econHandler) {
        this.plotAreaManager = plotAreaManager;
        this.econHandler = econHandler;
    }

    private String[] getArgumentList(PlotPlayer player) {
        List<String> args = new ArrayList<>();
        if (this.econHandler != null && Permissions
            .hasPermission(player, Captions.PERMISSION_LIST_FOR_SALE)) {
            args.add("forsale");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_MINE)) {
            args.add("mine");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_SHARED)) {
            args.add("shared");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_WORLD)) {
            args.add("world");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_TOP)) {
            args.add("top");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_ALL)) {
            args.add("all");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_UNOWNED)) {
            args.add("unowned");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_PLAYER)) {
            args.add("<player>");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_WORLD)) {
            args.add("<world>");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_DONE)) {
            args.add("done");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_EXPIRED)) {
            args.add("expired");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_FUZZY)) {
            args.add("fuzzy <search...>");
        }
        return args.toArray(new String[args.size()]);
    }

    public void noArgs(PlotPlayer<?> player) {
        player.sendMessage(TranslatableCaption.of("commandconfig.subcommand_set_options_header"),
                           Templates.of("values", Arrays.toString(getArgumentList(player))));
    }

    @Override public boolean onCommand(PlotPlayer<?> player, String[] args) {
        if (args.length < 1) {
            noArgs(player);
            return false;
        }

        final int page;
        if (args.length > 1) {
            int tempPage = -1;
            try {
                tempPage = Integer.parseInt(args[args.length - 1]);
                --tempPage;
                if (tempPage < 0) {
                    tempPage = 0;
                }
            } catch (NumberFormatException ignored) {
            }
            page = tempPage;
        } else {
            page = 0;
        }

        String world = player.getLocation().getWorldName();
        PlotArea area = player.getApplicablePlotArea();
        String arg = args[0].toLowerCase();
        final boolean[] sort = new boolean[] {true};

        final Consumer<PlotQuery> plotConsumer = query -> {
            if (query == null) {
                sendMessage(player, Captions.DID_YOU_MEAN,
                    new StringComparison<>(args[0], new String[] {"mine", "shared", "world", "all"})
                        .getBestMatch());
                return;
            }

            if (area != null) {
                query.relativeToArea(area);
            }

            if (sort[0]) {
                query.withSortingStrategy(SortingStrategy.SORT_BY_CREATION);
            }

            final List<Plot> plots = query.asList();

            if (plots.isEmpty()) {
                player.sendMessage(TranslatableCaption.of("invalid.found_no_plots"));
                return;
            }
            displayPlots(player, plots, 12, page, args);
        };

        switch (arg) {
            case "mine":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_MINE)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.mine"));
                    return false;
                }
                sort[0] = false;
                plotConsumer.accept(PlotQuery.newQuery().ownedBy(player).whereBasePlot().withSortingStrategy(SortingStrategy.SORT_BY_TEMP));
                break;
            case "shared":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_SHARED)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.shared"));
                    return false;
                }
                plotConsumer.accept(PlotQuery.newQuery().withMember(player.getUUID()).thatPasses(plot -> !plot.isOwnerAbs(player.getUUID())));
                break;
            case "world":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_WORLD)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.world"));
                    return false;
                }
                if (!Permissions.hasPermission(player, "plots.list.world." + world)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.world" + world));
                    return false;
                }
                plotConsumer.accept(PlotQuery.newQuery().inWorld(world));
                break;
            case "expired":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_EXPIRED)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.expired"));
                    return false;
                }
                if (ExpireManager.IMP == null) {
                    plotConsumer.accept(PlotQuery.newQuery().noPlots());
                } else {
                    plotConsumer.accept(PlotQuery.newQuery().expiredPlots());
                }
                break;
            case "area":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_AREA)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.area"));
                    return false;
                }
                if (!Permissions.hasPermission(player, "plots.list.world." + world)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.world" + world));
                    return false;
                }
                if (area == null) {
                    plotConsumer.accept(PlotQuery.newQuery().noPlots());
                } else {
                    plotConsumer.accept(PlotQuery.newQuery().inArea(area));
                }
                break;
            case "all":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_ALL)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.all"));
                    return false;
                }
                plotConsumer.accept(PlotQuery.newQuery().allPlots());
                break;
            case "done":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_DONE)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.done"));
                    return false;
                }
                sort[0] = false;
                plotConsumer.accept(PlotQuery.newQuery().allPlots().thatPasses(DoneFlag::isDone).withSortingStrategy(SortingStrategy.SORT_BY_DONE));
                break;
            case "top":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_TOP)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.top"));
                    return false;
                }
                sort[0] = false;
                plotConsumer.accept(PlotQuery.newQuery().allPlots().withSortingStrategy(SortingStrategy.SORT_BY_RATING));
                break;
            case "forsale":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_FOR_SALE)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.forsale"));
                    return false;
                }
                if (this.econHandler == null) {
                    break;
                }
                plotConsumer.accept(PlotQuery.newQuery().allPlots().thatPasses(plot -> plot.getFlag(PriceFlag.class) > 0));
                break;
            case "unowned":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_UNOWNED)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.unowned"));
                    return false;
                }
                plotConsumer.accept(PlotQuery.newQuery().allPlots().thatPasses(plot -> plot.getOwner() == null));
                break;
            case "fuzzy":
                if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_FUZZY)) {
                    player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                       Templates.of("node", "plots.list.fuzzy"));
                    return false;
                }
                if (args.length < (page == -1 ? 2 : 3)) {
                    player.sendMessage(TranslatableCaption.of("commandconfig.command_syntax"),
                                       Templates.of("value", "/plot list fuzzy <search...> [#]"));
                    return false;
                }
                String term;
                if (MathMan.isInteger(args[args.length - 1])) {
                    term = StringMan.join(Arrays.copyOfRange(args, 1, args.length - 1), " ");
                } else {
                    term = StringMan.join(Arrays.copyOfRange(args, 1, args.length), " ");
                }
                sort[0] = false;
                plotConsumer.accept(PlotQuery.newQuery().plotsBySearch(term));
                break;
            default:
                if (this.plotAreaManager.hasPlotArea(args[0])) {
                    if (!Permissions.hasPermission(player, Captions.PERMISSION_LIST_WORLD)) {
                        player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                           Templates.of("node", "plots.list.world"));
                        return false;
                    }
                    if (!Permissions.hasPermission(player, "plots.list.world." + args[0])) {
                        player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                           Templates.of("node", "plots.list.world." + args[0]));
                        return false;
                    }
                    plotConsumer.accept(PlotQuery.newQuery().inWorld(args[0]));
                    break;
                }

                PlotSquared.get().getImpromptuUUIDPipeline()
                    .getSingle(args[0], (uuid, throwable) -> {
                        if (throwable instanceof TimeoutException) {
                            player.sendMessage(TranslatableCaption.of("players.fetching_players_timeout"));
                        } else if (throwable != null) {
                            if (uuid == null) {
                                try {
                                    uuid = UUID.fromString(args[0]);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        if (uuid == null) {
                            player.sendMessage(TranslatableCaption.of("errors.invalid_player"),
                                               Templates.of("value", args[0]));
                        } else {
                            if (!Permissions
                                .hasPermission(player, Captions.PERMISSION_LIST_PLAYER)) {
                                player.sendMessage(TranslatableCaption.of("permission.no_permission"),
                                                   Templates.of("node", "plots.list.player"));
                            } else {
                                sort[0] = false;
                                plotConsumer.accept(PlotQuery.newQuery().ownedBy(uuid).withSortingStrategy(SortingStrategy.SORT_BY_TEMP));
                            }
                        }
                    });
        }

        return true;
    }

    public void displayPlots(final PlotPlayer player, List<Plot> plots, int pageSize, int page, String[] args) {
        // Header
        plots.removeIf(plot -> !plot.isBasePlot());
        this.paginate(player, plots, pageSize, page,
            new RunnableVal3<Integer, Plot, PlotMessage>() {
                @Override public void run(Integer i, Plot plot, PlotMessage message) {
                    String color;
                    if (plot.getOwner() == null) {
                        color = "$3";
                    } else if (plot.isOwner(player.getUUID())) {
                        color = "$1";
                    } else if (plot.isAdded(player.getUUID())) {
                        color = "$4";
                    } else if (plot.isDenied(player.getUUID())) {
                        color = "$2";
                    } else {
                        color = "$1";
                    }
                    PlotMessage trusted = new PlotMessage().text(Captions.color(
                        Captions.PLOT_INFO_TRUSTED.getTranslated()
                            .replaceAll("%trusted%", PlayerManager.getPlayerList(plot.getTrusted()))))
                        .color("$1");
                    PlotMessage members = new PlotMessage().text(Captions.color(
                        Captions.PLOT_INFO_MEMBERS.getTranslated()
                            .replaceAll("%members%", PlayerManager.getPlayerList(plot.getMembers()))))
                        .color("$1");
                    message.text("[").color("$3").text(i + "")
                        .command("/plot visit " + plot.getArea() + ";" + plot.getId())
                        .tooltip("/plot visit " + plot.getArea() + ";" + plot.getId()).color("$1")
                        .text("]").color("$3").text(" " + plot.toString()).tooltip(trusted, members)
                        .command("/plot info " + plot.getArea() + ";" + plot.getId()).color(color)
                        .text(" - ").color("$2");
                    String prefix = "";

                    try {
                        final List<UUIDMapping> names = PlotSquared.get().getImpromptuUUIDPipeline()
                            .getNames(plot.getOwners()).get(Settings.UUID.BLOCKING_TIMEOUT, TimeUnit.MILLISECONDS);
                        for (final UUIDMapping uuidMapping : names) {
                            PlotPlayer<?> pp = PlotSquared.platform().getPlayerManager().getPlayerIfExists(uuidMapping.getUuid());
                            if (pp != null) {
                                message = message.text(prefix).color("$4").text(uuidMapping.getUsername()).color("$1")
                                    .tooltip(new PlotMessage("Online").color("$4"));
                            } else {
                                message = message.text(prefix).color("$4").text(uuidMapping.getUsername()).color("$1")
                                    .tooltip(new PlotMessage("Offline").color("$3"));
                            }
                            prefix = ", ";
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        final StringBuilder playerBuilder = new StringBuilder();
                        final Iterator<UUID> uuidIterator = plot.getOwners().iterator();
                        while (uuidIterator.hasNext()) {
                            final UUID uuid = uuidIterator.next();
                            playerBuilder.append(uuid);
                            if (uuidIterator.hasNext()) {
                                playerBuilder.append(", ");
                            }
                        }
                        player.sendMessage(TranslatableCaption.of("errors.invalid_player"),
                            Templates.of("value", playerBuilder.toString()));
                    } catch (TimeoutException e) {
                        player.sendMessage(TranslatableCaption.of("players.fetching_players_timeout"));
                    }
                }
            }, "/plot list " + args[0], Captions.PLOT_LIST_HEADER_PAGED.getTranslated());
    }

    @Override public Collection<Command> tab(PlotPlayer player, String[] args, boolean space) {
        final List<String> completions = new LinkedList<>();
        if (this.econHandler != null && Permissions
            .hasPermission(player, Captions.PERMISSION_LIST_FOR_SALE)) {
            completions.add("forsale");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_MINE)) {
            completions.add("mine");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_SHARED)) {
            completions.add("shared");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_WORLD)) {
            completions.addAll(PlotSquared.platform().getWorldManager().getWorlds());
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_TOP)) {
            completions.add("top");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_ALL)) {
            completions.add("all");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_UNOWNED)) {
            completions.add("unowned");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_DONE)) {
            completions.add("done");
        }
        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_EXPIRED)) {
            completions.add("expired");
        }

        final List<Command> commands = new LinkedList<>();
        commands.addAll(completions.stream()
            .filter(completion -> completion.toLowerCase().startsWith(args[0].toLowerCase()))
            .map(completion -> new Command(null, true, completion, "", RequiredType.NONE, CommandCategory.TELEPORT) {})
            .collect(Collectors.toList()));

        if (Permissions.hasPermission(player, Captions.PERMISSION_LIST_PLAYER) && args[0].length() > 0) {
            commands.addAll(TabCompletions.completePlayers(args[0], Collections.emptyList()));
        }

        return commands;
    }

}
