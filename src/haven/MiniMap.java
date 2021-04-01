/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import java.awt.Color;
import haven.MapFile.Segment;
import haven.MapFile.DataGrid;
import haven.MapFile.Grid;
import haven.MapFile.GridInfo;
import haven.MapFile.Marker;
import haven.MapFile.PMarker;
import haven.MapFile.SMarker;
import haven.purus.alarms.AlarmManager;
import haven.purus.mapper.Mapper;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;
import static haven.OCache.posres;

public class MiniMap extends Widget {
    public static final Tex bg = Resource.loadtex("gfx/hud/mmap/ptex");
    public static final Tex nomap = Resource.loadtex("gfx/hud/mmap/nomap");
    public static final Tex mapgrid = Resource.loadtex("hud/mmap/mapgrid");

	public boolean showGrid = false;

	public static final Tex plp = ((TexI)Resource.loadtex("gfx/hud/mmap/plp")).filter(haven.render.Texture.Filter.LINEAR);
    public final MapFile file;
    public Location curloc;
    public Location sessloc;
    public GobIcon.Settings iconconf;
    public List<DisplayIcon> icons = Collections.emptyList();
    protected Locator setloc;
    protected boolean follow;
    protected int zoomlevel = 0;
    protected DisplayGrid[] display;
    protected Area dgext, dtext;
    protected Segment dseg;
    protected int dlvl;
    protected Location dloc;
    private static final HashSet<Long> alarmPlayed = new HashSet<Long>();
    private static final HashSet<Long> sent = new HashSet<>();

    public MiniMap(Coord sz, MapFile file) {
	super(sz);
	this.file = file;
    }

    public MiniMap(MapFile file) {
	this(Coord.z, file);
    }

    protected void attached() {
	if(iconconf == null) {
	    GameUI gui = getparent(GameUI.class);
	    if(gui != null)
		iconconf = gui.iconconf;
	}
	super.attached();
    }

    public static class Location {
	public final Segment seg;
	public final Coord tc;

	public Location(Segment seg, Coord tc) {
	    Objects.requireNonNull(seg);
	    Objects.requireNonNull(tc);
	    this.seg = seg; this.tc = tc;
	}
    }

    public interface Locator {
	Location locate(MapFile file) throws Loading;
    }

    public static class SessionLocator implements Locator {
	public final Session sess;
	private MCache.Grid lastgrid = null;
	private Location lastloc;

	public SessionLocator(Session sess) {this.sess = sess;}

	public Location locate(MapFile file) {
	    MCache map = sess.glob.map;
	    if(lastgrid != null) {
		synchronized(map.grids) {
		    if(map.grids.get(lastgrid.gc) == lastgrid)
			return(lastloc);
		}
		lastgrid = null;
		lastloc = null;
	    }
	    Collection<MCache.Grid> grids = new ArrayList<>();
	    synchronized(map.grids) {
		grids.addAll(map.grids.values());
	    }
	    for(MCache.Grid grid : grids) {
		GridInfo info = file.gridinfo.get(grid.id);
		if(info == null)
		    continue;
		Segment seg = file.segments.get(info.seg);
		if(seg != null) {
		    Location ret = new Location(seg, info.sc.sub(grid.gc).mul(cmaps));
		    lastgrid = grid;
		    lastloc = ret;
		    return(ret);
		}
	    }
	    throw(new Loading("No mapped grids found."));
	}
    }

    public static class MapLocator implements Locator {
	public final MapView mv;

	public MapLocator(MapView mv) {this.mv = mv;}

	public Location locate(MapFile file) {
	    Coord mc = new Coord2d(mv.getcc()).floor(MCache.tilesz);
	    if(mc == null)
		throw(new Loading("Waiting for initial location"));
	    MCache.Grid plg = mv.ui.sess.glob.map.getgrid(mc.div(cmaps));
	    GridInfo info = file.gridinfo.get(plg.id);
	    if(info == null)
		throw(new Loading("No grid info, probably coming soon"));
	    Segment seg = file.segments.get(info.seg);
	    if(seg == null)
		throw(new Loading("No segment info, probably coming soon"));
	    return(new Location(seg, info.sc.mul(cmaps).add(mc.sub(plg.ul))));
	}
    }

    public static class SpecLocator implements Locator {
	public final long seg;
	public final Coord tc;

	public SpecLocator(long seg, Coord tc) {this.seg = seg; this.tc = tc;}

	public Location locate(MapFile file) {
	    Segment seg = file.segments.get(this.seg);
	    if(seg == null)
		return(null);
	    return(new Location(seg, tc));
	}
    }

    public void center(Location loc) {
	curloc = loc;
    }

    public Location resolve(Locator loc) {
	if(!file.lock.readLock().tryLock())
	    throw(new Loading("Map file is busy"));
	try {
	    return(loc.locate(file));
	} finally {
	    file.lock.readLock().unlock();
	}
    }

    public Coord xlate(Location loc) {
	Location dloc = this.dloc;
	if((dloc == null) || (dloc.seg != loc.seg))
	    return(null);
	return(loc.tc.sub(dloc.tc).div(scalef()).add(sz.div(2)));
    }

    public Location xlate(Coord sc) {
	Location dloc = this.dloc;
	if(dloc == null)
	    return(null);
	Coord tc = sc.sub(sz.div(2)).mul(scalef()).add(dloc.tc);
	return(new Location(dloc.seg, tc));
    }

    private Locator sesslocator;
    public void tick(double dt) {
	if(setloc != null) {
	    try {
		Location loc = resolve(setloc);
		center(loc);
		if(!follow)
		    setloc = null;
	    } catch(Loading l) {
	    }
	}
	if((sesslocator == null) && (ui != null) && (ui.sess != null))
	    sesslocator = new SessionLocator(ui.sess);
	if(sesslocator != null) {
	    try {
		sessloc = resolve(sesslocator);
	    } catch(Loading l) {
	    }
	}
	icons = findicons(icons);
    }

    public void center(Locator loc) {
	setloc = loc;
	follow = false;
    }

    public void follow(Locator loc) {
	setloc = loc;
	follow = true;
    }

    public class DisplayIcon {
	public final GobIcon icon;
	public final Gob gob;
	public final GobIcon.Image img;
	public Coord2d rc = null;
	public Coord sc = null;
	public double ang = 0.0;
	public Color col = Color.WHITE;
	public int z;
	public double stime;

	public DisplayIcon(GobIcon icon) {
	    this.icon = icon;
	    this.gob = icon.gob;
	    this.img = icon.img();
		this.z = this.img.z;
	    this.stime = Utils.rtime();
	}

	public void update(Coord2d rc, double ang) {
			try {
				if(icon.res.get().name.equals("gfx/hud/mmap/cave") && !sent.contains(gob.id)) {
					if(gameui().ui.sess.glob.map.grids != null) {
						MCache.Grid g;
						g = gameui().ui.sess.glob.map.getgrid(gob.rc.floor().div(11 * 100));
						Mapper.sendMarkerData(g.id, gob.rc.div(11).floor().mod(new Coord(100, 100)).x, gob.rc.div(11).floor().mod(new Coord(100, 100)).y, "gfx/hud/mmap/cave", "Cave");
						sent.add(gob.id);
					}
				}
			} catch(Loading l){}
	    this.rc = rc;
	    this.ang = ang;
	    if((this.rc == null) || (sessloc == null) || (dloc == null) || (dloc.seg != sessloc.seg))
		this.sc = null;
	    else
		this.sc = p2c(this.rc);
	}
    }

    public static class MarkerID extends GAttrib {
	public final long id;

	public MarkerID(Gob gob, long id) {
	    super(gob);
	    this.id = id;
	}

	public static Gob find(OCache oc, long id) {
	    synchronized(oc) {
		for(Gob gob : oc) {
		    MarkerID iattr = gob.getattr(MarkerID.class);
		    if((iattr != null) && (iattr.id == id))
			return(gob);
		}
	    }
	    return(null);
	}
    }

    public static class DisplayMarker {
	public static final Resource.Image flagbg, flagfg;
	public static final Coord flagcc;
	public final Marker m;
	public final Text tip;
	public Area hit;
	private Resource.Image img;
	private Coord imgsz;
	private Coord cc;

	static {
	    Resource flag = Resource.local().loadwait("gfx/hud/mmap/flag");
	    flagbg = flag.layer(Resource.imgc, 1);
	    flagfg = flag.layer(Resource.imgc, 0);
	    flagcc = UI.scale(flag.layer(Resource.negc).cc);
	}

	public DisplayMarker(Marker marker) {
	    this.m = marker;
	    this.tip = Text.render(m.nm);
	    if(marker instanceof PMarker)
		this.hit = Area.sized(flagcc.inv(), UI.scale(flagbg.sz));
	}

	public void draw(GOut g, Coord c) {
	    if(m instanceof PMarker) {
		Coord ul = c.sub(flagcc);
		g.chcolor(((PMarker)m).color);
		g.image(flagfg, ul);
		g.chcolor();
		g.image(flagbg, ul);
	    } else if(m instanceof SMarker) {
		SMarker sm = (SMarker)m;
		try {
		    if(cc == null) {
			Resource res = sm.res.loadsaved(Resource.remote());
			img = res.layer(Resource.imgc);
			Resource.Neg neg = res.layer(Resource.negc);
			cc = (neg != null) ? neg.cc : img.ssz.div(2);
			if(hit == null)
			    hit = Area.sized(cc.inv(), img.ssz);
		    }
		} catch(Loading l) {
		} catch(Exception e) {
		    cc = Coord.z;
		}
		if(img != null)
		    g.image(img, c.sub(cc));
	    }
	}
    }

    public static class DisplayGrid {
	public final MapFile file;
	public final Segment seg;
	public final Coord sc;
	public final Area mapext;
	public final Indir<? extends DataGrid> gref;
	private DataGrid cgrid = null;
	private Tex img = null;
	private Defer.Future<Tex> nextimg = null;

	public DisplayGrid(Segment seg, Coord sc, int lvl, Indir<? extends DataGrid> gref) {
	    this.file = seg.file();
	    this.seg = seg;
	    this.sc = sc;
	    this.gref = gref;
	    mapext = Area.sized(sc.mul(cmaps.mul(1 << lvl)), cmaps.mul(1 << lvl));
	}

	public Tex img() {
	    DataGrid grid = gref.get();
	    if(grid != cgrid) {
		if(nextimg != null)
		    nextimg.cancel();
		if(grid instanceof MapFile.ZoomGrid) {
		    nextimg = Defer.later(() -> new TexI(grid.render(sc.mul(cmaps))));
		} else {
		    nextimg = Defer.later(new Defer.Callable<Tex>() {
			    MapFile.View view = new MapFile.View(seg);

			    public TexI call() {
				try(Locked lk = new Locked(file.lock.readLock())) {
				    for(int y = -1; y <= 1; y++) {
					for(int x = -1; x <= 1; x++) {
					    view.addgrid(sc.add(x, y));
					}
				    }
				    view.fin();
				    return(new TexI(MapSource.drawmap(view, Area.sized(sc.mul(cmaps), cmaps))));
				}
			    }
			});
		}
		cgrid = grid;
	    }
	    if(nextimg != null) {
		try {
		    img = nextimg.get();
		} catch(Loading l) {}
	    }
	    return(img);
	}

	private Collection<DisplayMarker> markers = Collections.emptyList();
	private int markerseq = -1;
	public Collection<DisplayMarker> markers(boolean remark) {
	    if(remark && (markerseq != file.markerseq)) {
		if(file.lock.readLock().tryLock()) {
		    try {
			ArrayList<DisplayMarker> marks = new ArrayList<>();
			for(Marker mark : file.markers) {
			    if((mark.seg == this.seg.id) && mapext.contains(mark.tc))
				marks.add(new DisplayMarker(mark));
			}
			marks.trimToSize();
			markers = (marks.size() == 0) ? Collections.emptyList() : marks;
			markerseq = file.markerseq;
		    } finally {
			file.lock.readLock().unlock();
		    }
		}
	    }
	    return(markers);
	}
    }

    private float scalef() {
	return(UI.unscale((float)(1 << dlvl)));
    }

    public Coord st2c(Coord tc) {
	return(UI.scale(tc.add(sessloc.tc).sub(dloc.tc).div(1 << dlvl)).add(sz.div(2)));
    }

    public Coord p2c(Coord2d pc) {
	return(st2c(pc.floor(tilesz)));
    }

    private void redisplay(Location loc) {
	Coord hsz = sz.div(2);
	Coord zmaps = cmaps.mul(1 << zoomlevel);
	Area next = Area.sized(loc.tc.sub(hsz.mul(UI.unscale((float)(1 << zoomlevel)))).div(zmaps),
	    UI.unscale(sz).div(cmaps).add(2, 2));
	if((display == null) || (loc.seg != dseg) || (zoomlevel != dlvl) || !next.equals(dgext)) {
	    DisplayGrid[] nd = new DisplayGrid[next.rsz()];
	    if((display != null) && (loc.seg == dseg) && (zoomlevel == dlvl)) {
		for(Coord c : dgext) {
		    if(next.contains(c))
			nd[next.ri(c)] = display[dgext.ri(c)];
		}
	    }
	    display = nd;
	    dseg = loc.seg;
	    dlvl = zoomlevel;
	    dgext = next;
	    dtext = Area.sized(next.ul.mul(zmaps), next.sz().mul(zmaps));
	}
	dloc = loc;
	if(file.lock.readLock().tryLock()) {
	    try {
		for(Coord c : dgext) {
		    if(display[dgext.ri(c)] == null)
			display[dgext.ri(c)] = new DisplayGrid(dloc.seg, c, dlvl, dloc.seg.grid(dlvl, c.mul(1 << dlvl)));
		}
	    } finally {
		file.lock.readLock().unlock();
	    }
	}
    }

    public void drawmap(GOut g) {
	Coord hsz = sz.div(2);
	for(Coord c : dgext) {
	    Tex img;
	    try {
		DisplayGrid disp = display[dgext.ri(c)];
		if((disp == null) || ((img = disp.img()) == null))
		    continue;
	    } catch(Loading l) {
		continue;
	    }
	    Coord ul = UI.scale(c.mul(cmaps)).sub(dloc.tc.div(scalef())).add(hsz);
	    g.image(img, ul, UI.scale(img.sz()));
	    if(showGrid && dlvl == 0) {
			g.image(mapgrid, ul);
		}
	}


	}

    public void drawmarkers(GOut g) {
	Coord hsz = sz.div(2);
	for(Coord c : dgext) {
	    DisplayGrid dgrid = display[dgext.ri(c)];
	    if(dgrid == null)
		continue;
	    for(DisplayMarker mark : dgrid.markers(true)) {
		if(filter(mark))
		    continue;
		mark.draw(g, mark.m.tc.sub(dloc.tc).div(scalef()).add(hsz));
	    }
	}
    }

    public List<DisplayIcon> findicons(Collection<? extends DisplayIcon> prev) {
	if((ui.sess == null) || (iconconf == null))
	    return(Collections.emptyList());
	Map<Gob, DisplayIcon> pmap = Collections.emptyMap();
	if(prev != null) {
	    pmap = new HashMap<>();
	    for(DisplayIcon disp : prev)
		pmap.put(disp.gob, disp);
	}
	List<DisplayIcon> ret = new ArrayList<>();
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    for(Gob gob : oc) {
		try {
		    GobIcon icon = gob.getattr(GobIcon.class);
		    if(icon != null) {
			GobIcon.Setting conf = iconconf.get(icon.res.get());
			if((conf != null) && conf.show) {
			    DisplayIcon disp = pmap.get(gob);
			    if(disp == null)
				disp = new DisplayIcon(icon);
			    disp.update(gob.rc, gob.a);
			    KinInfo kin = gob.getattr(KinInfo.class);
			    if((kin != null) && (kin.group < BuddyWnd.gc.length))
				disp.col = BuddyWnd.gc[kin.group];
			    ret.add(disp);
			}
		    }
		} catch(Loading l) {}
			try {
				Resource res = gob.getres();
				if(alarmPlayed.contains(gob.id))
					continue;
				if(res != null && AlarmManager.play(res.name, gob))
					alarmPlayed.add(gob.id);
				if(gob.type == Gob.Type.PLAYER && gob.id != ui.gui.plid) {
					KinInfo kin = gob.getattr(KinInfo.class);
					if(kin == null) {
						alarmPlayed.add(gob.id);
						Audio.play(Resource.local().loadwait("sfx/alarms/whitePlayer"));
					} else if(kin.group == 2) {
						alarmPlayed.add(gob.id);
						Audio.play(Resource.local().loadwait("sfx/alarms/redPlayer"));
					}
				}
			} catch(Loading l) {}
	    }
	}
	Collections.sort(ret, (a, b) -> a.z - b.z);
	if(ret.size() == 0)
	    return(Collections.emptyList());
	return(ret);
    }

    public void drawicons(GOut g) {
	if((sessloc == null) || (dloc.seg != sessloc.seg))
	    return;
	for(DisplayIcon disp : icons) {
	    if((disp.sc == null) || filter(disp))
		continue;
	    GobIcon.Image img = disp.img;
	    if(disp.col != null)
		g.chcolor(disp.col);
	    else
		g.chcolor();
	    if(!img.rot)
		g.image(img.tex, disp.sc.sub(img.cc));
	    else
		g.rotimage(img.tex, disp.sc, img.cc, -disp.ang + img.ao);
	}
	g.chcolor();
    }

    public void remparty() {
	Set<Gob> memb = new HashSet<>();
	synchronized(ui.sess.glob.party.memb) {
	    for(Party.Member m : ui.sess.glob.party.memb.values()) {
		Gob gob = m.getgob();
		if(gob != null)
		    memb.add(gob);
	    }
	}
	for(Iterator<DisplayIcon> it = icons.iterator(); it.hasNext();) {
	    DisplayIcon icon = it.next();
	    if(memb.contains(icon.gob))
		it.remove();
	}
    }

    public void drawparty(GOut g) {
	synchronized(ui.sess.glob.party.memb) {
	    for(Party.Member m : ui.sess.glob.party.memb.values()) {
		try {
		    Coord2d ppc = m.getc();
		    if(ppc == null)
			continue;
		    g.chcolor(m.col.getRed(), m.col.getGreen(), m.col.getBlue(), 255);
		    g.rotimage(plp, p2c(ppc), plp.sz().div(2), -m.geta() - (Math.PI / 2));
		    g.chcolor();
		} catch(Loading l) {}
	    }
	}
    }

    public void drawparts(GOut g){
	drawmap(g);
	drawmarkers(g);
	if(dlvl == 0)
	    drawicons(g);
	drawparty(g);
    }

    public void draw(GOut g) {
	Location loc = this.curloc;
	if(loc == null)
	    return;
	redisplay(loc);
	remparty();
	drawparts(g);
    }

    private static boolean hascomplete(DisplayGrid[] disp, Area dext, Coord c) {
	DisplayGrid dg = disp[dext.ri(c)];
	if(dg == null)
	    return(false);
	return(dg.gref.get() != null);
    }

    protected boolean allowzoomout() {
	DisplayGrid[] disp = this.display;
	Area dext = this.dgext;
	try {
	    for(int x = dext.ul.x; x < dext.br.x; x++) {
		if(hascomplete(disp, dext, new Coord(x, dext.ul.y)) ||
		   hascomplete(disp, dext, new Coord(x, dext.br.y - 1)))
		    return(true);
	    }
	    for(int y = dext.ul.y; y < dext.br.y; y++) {
		if(hascomplete(disp, dext, new Coord(dext.ul.x, y)) ||
		   hascomplete(disp, dext, new Coord(dext.br.x - 1, y)))
		    return(true);
	    }
	} catch(Loading l) {
	    return(false);
	}
	return(false);
    }

    public DisplayIcon iconat(Coord c) {
	for(ListIterator<DisplayIcon> it = icons.listIterator(icons.size()); it.hasPrevious();) {
	    DisplayIcon disp = it.previous();
	    GobIcon.Image img = disp.img;
	    if((disp.sc != null) && c.isect(disp.sc.sub(img.cc), img.tex.sz()) && !filter(disp))
		return(disp);
	}
	return(null);
    }

    public DisplayMarker findmarker(long id) {
	for(DisplayGrid dgrid : display) {
	    if(dgrid == null)
		continue;
	    for(DisplayMarker mark : dgrid.markers(false)) {
		if((mark.m instanceof SMarker) && (((SMarker)mark.m).oid == id))
		    return(mark);
	    }
	}
	return(null);
    }

    public DisplayMarker markerat(Coord tc) {
	for(DisplayGrid dgrid : display) {
	    if(dgrid == null)
		continue;
	    for(DisplayMarker mark : dgrid.markers(false)) {
		if((mark.hit != null) && mark.hit.contains(tc.sub(mark.m.tc).div(scalef())) && !filter(mark))
		    return(mark);
	    }
	}
	return(null);
    }

    public boolean filter(DisplayIcon icon) {
	MarkerID iattr = icon.gob.getattr(MarkerID.class);
	if((iattr != null) && (findmarker(iattr.id) != null))
	    return(true);
	return(false);
    }

    public boolean filter(DisplayMarker marker) {
	return(false);
    }

    public boolean clickloc(Location loc, int button, boolean press) {
	return(false);
    }

    public boolean clickicon(DisplayIcon icon, Location loc, int button, boolean press) {
	return(false);
    }

    public boolean clickmarker(DisplayMarker mark, Location loc, int button, boolean press) {
	return(false);
    }

    private UI.Grab drag;
    private boolean dragging;
    private Coord dsc, dmc;
    public boolean dragp(int button) {
	return(button == 1);
    }

    private Location dsloc;
    private DisplayIcon dsicon;
    private DisplayMarker dsmark;
    public boolean mousedown(Coord c, int button) {
	dsloc = xlate(c);
	if(dsloc != null) {
	    dsicon = iconat(c);
	    dsmark = markerat(dsloc.tc);
	    if((dsicon != null) && clickicon(dsicon, dsloc, button, true))
		return(true);
	    if((dsmark != null) && clickmarker(dsmark, dsloc, button, true))
		return(true);
	    if(clickloc(dsloc, button, true))
		return(true);
	} else {
	    dsloc = null;
	    dsicon = null;
	    dsmark = null;
	}
	if(dragp(button)) {
	    Location loc = curloc;
	    if((drag == null) && (loc != null)) {
		drag = ui.grabmouse(this);
		dsc = c;
		dmc = loc.tc;
		dragging = false;
	    }
	    return(true);
	}
	return(super.mousedown(c, button));
    }

    public void mousemove(Coord c) {
	if(drag != null) {
	    if(dragging) {
		setloc = null;
		follow = false;
		curloc = new Location(curloc.seg, dmc.add(dsc.sub(c).mul(scalef())));
	    } else if(c.dist(dsc) > 5) {
		dragging = true;
	    }
	}
	super.mousemove(c);
    }

    public boolean mouseup(Coord c, int button) {
	if((drag != null) && (button == 1)) {
	    drag.remove();
	    drag = null;
	}
	release: if(!dragging && (dsloc != null)) {
	    if((dsicon != null) && clickicon(dsicon, dsloc, button, false))
		break release;
	    if((dsmark != null) && clickmarker(dsmark, dsloc, button, false))
		break release;
	    if(clickloc(dsloc, button, false))
		break release;
	}
	dsloc = null;
	dsicon = null;
	dsmark = null;
	dragging = false;
	return(super.mouseup(c, button));
    }

    public boolean mousewheel(Coord c, int amount) {
	if(amount > 0) {
	    if(allowzoomout())
		zoomlevel = Math.min(zoomlevel + 1, dlvl + 1);
	} else {
	    zoomlevel = Math.max(zoomlevel - 1, 0);
	}
	return(true);
    }

    public Object tooltip(Coord c, Widget prev) {
	if(dloc != null) {
	    Coord tc = c.sub(sz.div(2)).mul(scalef()).add(dloc.tc);
	    DisplayMarker mark = markerat(tc);
	    if(mark != null) {
		return(mark.tip);
	    }
	}
	return(super.tooltip(c, prev));
    }

    public void mvclick(MapView mv, Coord mc, Location loc, Gob gob, int button) {
	if(mc == null) mc = ui.mc;
	if((sessloc != null) && (sessloc.seg == loc.seg)) {
	    if(gob == null)
		mv.wdgmsg("click", mc,
			  loc.tc.sub(sessloc.tc).mul(tilesz).add(tilesz.div(2)).floor(posres),
			  button, ui.modflags());
	    else
		mv.wdgmsg("click", mc,
			  loc.tc.sub(sessloc.tc).mul(tilesz).add(tilesz.div(2)).floor(posres),
			  button, ui.modflags(), 0,
			  (int)gob.id,
			  gob.rc.floor(posres),
			  0, -1);
	}
    }
}
