package io.anuke.mindustry.maps;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Array;
import io.anuke.mindustry.content.Items;
import io.anuke.mindustry.core.GameState.State;
import io.anuke.mindustry.entities.units.BaseUnit;
import io.anuke.mindustry.game.Difficulty;
import io.anuke.mindustry.game.Team;
import io.anuke.mindustry.io.SaveIO;
import io.anuke.mindustry.maps.generation.WorldGenerator.GenResult;
import io.anuke.mindustry.maps.missions.Mission;
import io.anuke.mindustry.maps.missions.WaveMission;
import io.anuke.mindustry.type.Item;
import io.anuke.mindustry.type.ItemStack;
import io.anuke.mindustry.world.ColorMapper;
import io.anuke.mindustry.world.Tile;
import io.anuke.ucore.core.Settings;
import io.anuke.ucore.entities.Entities;
import io.anuke.ucore.entities.EntityGroup;
import io.anuke.ucore.entities.trait.Entity;
import io.anuke.ucore.util.Bits;
import io.anuke.ucore.util.GridMap;
import io.anuke.ucore.util.Log;
import io.anuke.ucore.util.Mathf;

import static io.anuke.mindustry.Vars.*;

public class Sectors{
    private static final int sectorImageSize = 32;
    private static final boolean checkExpansion = false;
    private static final float sectorLargeChance = 0.24f;

    private GridMap<Sector> grid = new GridMap<>();

    public void playSector(Sector sector){
        if(sector.hasSave() && SaveIO.breakingVersions.contains(sector.getSave().getBuild())){
            sector.getSave().delete();
            ui.showInfo("$text.save.old");
        }

        if(!sector.hasSave()){
            for(Mission mission : sector.missions){
                mission.reset();
            }
            world.loadSector(sector);
            logic.play();
            sector.saveID = control.getSaves().addSave("sector-" + sector.packedPosition()).index;
            world.sectors().save();
            world.setSector(sector);
            sector.currentMission().onBegin();
        }else if(SaveIO.breakingVersions.contains(sector.getSave().getBuild())){
            ui.showInfo("$text.save.old");
        }else try{
            sector.getSave().load();
            world.setSector(sector);
            state.set(State.playing);
            sector.currentMission().onBegin();
        }catch(Exception e){
            Log.err(e);
            sector.getSave().delete();

            playSector(sector);

            if(!headless){
                threads.runGraphics(() -> ui.showError("$text.sector.corrupted"));
            }
        }

    }

    /**If a sector is not yet unlocked, returns null.*/
    public Sector get(int x, int y){
        return grid.get(x, y);
    }

    public Sector get(int position){
        return grid.get(Bits.getLeftShort(position), Bits.getRightShort(position));
    }

    /**Tries to a sector in a specific direciton, specified by expandX and expandY.
     * The player *must* currently be playing in this sector.
     * If a sector is in that direction, this method will return false (failure)
     * @param sector the sector to expand
     * @param expandX spaces in X coordinate to expand, can be negative
     * @param expandY spaces in Y coordinate to expand, can be negative*/
    public boolean expandSector(Sector sector, int expandX, int expandY){
        if(world.getSector() != sector){
            throw new IllegalArgumentException("Sector is not being played in!");
        }

        //remove old sector data to clear things up
        for(int x = sector.x; x < sector.x+sector.width; x++){
            for(int y = sector.y; y < sector.y+sector.height; y++){
                grid.put(x, y, null);
            }
        }
        
        if(expandX < 0) sector.x += expandX;
        if(expandY < 0) sector.y += expandY;

        sector.width += Math.abs(expandX);
        sector.height += Math.abs(expandY);

        if(checkExpansion) {
            for (int x = sector.x; x < sector.x + sector.width; x++) {
                for (int y = sector.y; y < sector.y + sector.height; y++) {
                    if (grid.get(x, y) != null && grid.get(x, y).complete) {
                        //if a completed sector is hit, expansion failed
                        if (expandX < 0) sector.x -= expandX;
                        if (expandY < 0) sector.y -= expandY;
                        sector.width -= Math.abs(expandX);
                        sector.height -= Math.abs(expandY);
                        return false;
                    }
                }
            }
        }

        //add new sector spaces
        for(int x = sector.x; x < sector.x+sector.width; x++){
            for(int y = sector.y; y < sector.y+sector.height; y++){
                grid.put(x, y, sector);
            }
        }

        //sector map data should now be shifted and generated
        int shiftX = expandX < 0 ? -expandX*sectorSize : 0;
        int shiftY = expandY < 0 ? -expandY*sectorSize : 0;

        for(EntityGroup<?> group : Entities.getAllGroups()){
            for(Entity entity : group.all()){
                entity.set(entity.getX() + shiftX * tilesize, entity.getY() + shiftY * tilesize);

                if(entity instanceof BaseUnit){
                    Tile spawner = ((BaseUnit) entity).getSpawner();
                    if(spawner == null) continue;
                    int i = spawner.packedPosition();
                    ((BaseUnit) entity).setIntSpawner(world.transform(i, world.width(), world.height(), sector.width*sectorSize, shiftX, shiftY));
                }
            }
        }

        if(!headless){
            renderer.fog().setLoadingOffset(shiftX, shiftY);
        }

        //create *new* tile array
        Tile[][] newTiles = new Tile[sector.width * sectorSize][sector.height * sectorSize];

        //shift existing tiles to new array
        for (int x = 0; x < (sector.width - Math.abs(expandX))*sectorSize; x++) {
            for (int y = 0; y < (sector.height - Math.abs(expandY))*sectorSize; y++) {
                Tile tile = world.rawTile(x, y);
                tile.x = (short)(x + shiftX);
                tile.y = (short)(y + shiftY);
                newTiles[x + shiftX][y + shiftY] = tile;
                tile.block().transformLinks(tile, world.width(), world.height(), sector.width*sectorSize, sector.height*sectorSize, shiftX, shiftY);
            }
        }

        world.beginMapLoad(newTiles);

        //create new tiles
        for (int sx = 0; sx < sector.width; sx++) {
            for (int sy = 0; sy < sector.height; sy++) {
                //if this sector is a 'new sector (not part of the current save data...)
                if(sx < -expandX || sy < -expandY || sx >= sector.width - expandX || sy >= sector.height - expandY){
                    GenResult result = new GenResult();
                    Array<Item> ores = getOres(sx + sector.x, sy + sector.y);
                    //gen tiles in sector
                    for (int x = 0; x < sectorSize; x++) {
                        for (int y = 0; y < sectorSize; y++) {
                            world.generator().generateTile(result, sx + sector.x, sy + sector.y, x, y, true, null, ores);
                            newTiles[sx * sectorSize + x][sy * sectorSize + y] = new Tile(x + sx * sectorSize, y + sy*sectorSize, result.floor.id, result.wall.id, (byte)0, (byte)0, result.elevation);
                        }
                    }
                }
            }
        }

        //end loading of map
        world.endMapLoad();

        threads.runGraphics(() -> createTexture(sector));

        return true;
    }

    public Difficulty getDifficulty(Sector sector){
        if(sector.difficulty == 0){
            //yes, this means hard tutorial difficulty
            //(((have fun)))
            return Difficulty.hard;
        }else if(sector.difficulty < 4){
            return Difficulty.normal;
        }else if(sector.difficulty < 9){
            return Difficulty.hard;
        }else{
            return Difficulty.insane;
        }
    }

    public Array<Item> getOres(int x, int y){
        if(x == 0 && y == 0){
            return Array.with(Items.copper);
        }else if(x == 1 && y == 0){
            return Array.with(Items.copper, Items.lead, Items.coal);
        }
        return Array.with(Items.copper);
    }

    /**Unlocks a sector. This shows nearby sectors.*/
    public void completeSector(int x, int y){
        createSector(x, y);
        Sector sector = get(x, y);
        sector.complete = true;

        for(int sx = 0; sx < sector.width + 2; sx++){
            for(int sy = 0; sy < sector.height + 2; sy++){
                if((sx == 0 || sy == 0 || sx == sector.width + 1 || sy == sector.height + 1) &&
                     !((sx == 0 && sy == 0)
                    || (sx == 0 && sy == sector.height+1)
                    || (sx == sector.width+1 && sy == 0)
                    || (sx == sector.width+1 && sy == sector.height+1))){
                    createSector(sector.x + sx - 1, sector.y + sy - 1);
                }
            }
        }
    }

    /**Creates a sector at a location if it is not present, but does not unlock it.*/
    public void createSector(int x, int y){

        if(grid.containsKey(x, y)) return;

        Sector sector = new Sector();
        sector.x = (short)x;
        sector.y = (short)y;
        sector.complete = false;
        sector.width = sector.height = 1;
        initSector(sector);

        for(int cx = 0; cx < sector.width; cx++){
            for(int cy = 0; cy < sector.height; cy++){
                grid.put(x + cx, y + cy, sector);
            }
        }

        if(sector.texture == null) createTexture(sector);
    }

    public void load(){
        for(Sector sector : grid.values()){
            sector.texture.dispose();
        }
        grid.clear();

        Array<Sector> out = Settings.getBinary("sectors", Array.class, () -> new Array<>());

        for(Sector sector : out){
            createTexture(sector);
            initSector(sector);
            for(int cx = 0; cx < sector.width; cx++){
                for(int cy = 0; cy < sector.height; cy++){
                    grid.put(sector.x + cx, sector.y + cy, sector);
                }
            }
        }

        if(out.size == 0){
            createSector(0, 0);
        }
    }

    public void save(){
        Array<Sector> out = new Array<>();

        for(Sector sector : grid.values()){
            out.add(sector);
        }

        Settings.putBinary("sectors", out);
        Settings.save();
    }

    private void initSector(Sector sector){
        sector.difficulty = (int)(Mathf.dst(sector.x, sector.y));

        if(sector.difficulty == 0){
            sector.missions.addAll(TutorialSector.getMissions());
        }else{
            sector.missions.add(new WaveMission(Math.min(sector.difficulty*5 + Mathf.randomSeed(sector.getSeed(), 0, 3)*5, 100)));
        }

        sector.spawns = new Array<>();

        for(Mission mission : sector.missions){
            sector.spawns.addAll(mission.getWaves(sector));
        }

        //set starter items
        if(sector.difficulty > 12){ //now with titanium
            sector.startingItems = Array.with(new ItemStack(Items.copper, 1900), new ItemStack(Items.lead, 500), new ItemStack(Items.densealloy, 470), new ItemStack(Items.silicon, 460), new ItemStack(Items.titanium, 230));
        }else if(sector.difficulty > 8){ //just more resources
            sector.startingItems = Array.with(new ItemStack(Items.copper, 1500), new ItemStack(Items.lead, 400), new ItemStack(Items.densealloy, 340), new ItemStack(Items.silicon, 250));
        }else if(sector.difficulty > 5){ //now with silicon
            sector.startingItems = Array.with(new ItemStack(Items.copper, 950), new ItemStack(Items.lead, 300), new ItemStack(Items.densealloy, 190), new ItemStack(Items.silicon, 140));
        }else if(sector.difficulty > 3){ //now with carbide
            sector.startingItems = Array.with(new ItemStack(Items.copper, 700), new ItemStack(Items.lead, 200), new ItemStack(Items.densealloy, 130));
        }else if(sector.difficulty > 1){ //more starter items for faster start
            sector.startingItems = Array.with(new ItemStack(Items.copper, 400), new ItemStack(Items.lead, 100));
        }else{ //empty default
            sector.startingItems = Array.with();
        }
    }

    private void createTexture(Sector sector){
        if(headless) return; //obviously not created or needed on server

        if(sector.texture != null){
            sector.texture.dispose();
        }

        Pixmap pixmap = new Pixmap(sectorImageSize * sector.width, sectorImageSize * sector.height, Format.RGBA8888);
        GenResult secResult = new GenResult();

        for(int x = 0; x < pixmap.getWidth(); x++){
            for(int y = 0; y < pixmap.getHeight(); y++){
                int toX = x * sectorSize / sectorImageSize;
                int toY = y * sectorSize / sectorImageSize;

                GenResult result = world.generator().generateTile(sector.x, sector.y, toX, toY, false);
                world.generator().generateTile(secResult, sector.x, sector.y, toX, ((y+1) * sectorSize / sectorImageSize), false, null, null);

                int color = ColorMapper.colorFor(result.floor, result.wall, Team.none, result.elevation, secResult.elevation > result.elevation ? (byte)(1 << 6) : (byte)0);
                pixmap.drawPixel(x, pixmap.getHeight() - 1 - y, color);
            }
        }

        sector.texture = new Texture(pixmap);
        pixmap.dispose();
    }
}
