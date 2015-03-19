/*
 * Copyright (c) 2008-2010, Matthias Mann
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution. * Neither the name of Matthias Mann nor
 * the names of its contributors may be used to endorse or promote products derived from this software without specific prior
 * written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.badlogic.gdx.graphics.g2d;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.StringTokenizer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.GlyphLayout.GlyphRun;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.AtlasRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.utils.StreamUtils;

/** Renders bitmap fonts. The font consists of 2 files: an image file or {@link TextureRegion} containing the glyphs and a file in
 * the AngleCode BMFont text format that describes where each glyph is on the image. Currently only a single image of glyphs is
 * supported.
 * <p>
 * Text is drawn using a {@link Batch}. Text can be cached in a {@link BitmapFontCache} for faster rendering of static text, which
 * saves needing to compute the location of each glyph each frame.
 * <p>
 * * The texture for a BitmapFont loaded from a file is managed. {@link #dispose()} must be called to free the texture when no
 * longer needed. A BitmapFont loaded using a {@link TextureRegion} is managed if the region's texture is managed. Disposing the
 * BitmapFont disposes the region's texture, which may not be desirable if the texture is still being used elsewhere.
 * <p>
 * The code was originally based on Matthias Mann's TWL BitmapFont class. Thanks for sharing, Matthias! :)
 * @author Nathan Sweet
 * @author Matthias Mann */
public class BitmapFont implements Disposable {
	static private final int LOG2_PAGE_SIZE = 9;
	static private final int PAGE_SIZE = 1 << LOG2_PAGE_SIZE;
	static private final int PAGES = 0x10000 / PAGE_SIZE;

	public static final char[] xChars = {'x', 'e', 'a', 'o', 'n', 's', 'r', 'c', 'u', 'm', 'v', 'w', 'z'};
	public static final char[] capChars = {'M', 'N', 'B', 'D', 'C', 'E', 'F', 'K', 'A', 'G', 'H', 'I', 'J', 'L', 'O', 'P', 'Q',
		'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'};

	final BitmapFontData data;
	TextureRegion[] regions;
	private final BitmapFontCache cache;
	private boolean flipped;
	private boolean integer;
	private boolean ownsTexture;
	private final TextBounds bounds = new TextBounds();

	/** Creates a BitmapFont using the default 15pt Arial font included in the libgdx JAR file. This is convenient to easily display
	 * text without bothering without generating a bitmap font yourself. */
	public BitmapFont () {
		this(Gdx.files.classpath("com/badlogic/gdx/utils/arial-15.fnt"),
			Gdx.files.classpath("com/badlogic/gdx/utils/arial-15.png"), false, true);
	}

	/** Creates a BitmapFont using the default 15pt Arial font included in the libgdx JAR file. This is convenient to easily display
	 * text without bothering without generating a bitmap font yourself.
	 * @param flip If true, the glyphs will be flipped for use with a perspective where 0,0 is the upper left corner. */
	public BitmapFont (boolean flip) {
		this(Gdx.files.classpath("com/badlogic/gdx/utils/arial-15.fnt"),
			Gdx.files.classpath("com/badlogic/gdx/utils/arial-15.png"), flip, true);
	}

	/** Creates a BitmapFont with the glyphs relative to the specified region. If the region is null, the glyph textures are loaded
	 * from the image file given in the font file. The {@link #dispose()} method will not dispose the region's texture in this
	 * case!
	 * <p>
	 * The font data is not flipped.
	 * @param fontFile the font definition file
	 * @param region The texture region containing the glyphs. The glyphs must be relative to the lower left corner (ie, the region
	 *           should not be flipped). If the region is null the glyph images are loaded from the image path in the font file. */
	public BitmapFont (FileHandle fontFile, TextureRegion region) {
		this(fontFile, region, false);
	}

	/** Creates a BitmapFont with the glyphs relative to the specified region. If the region is null, the glyph textures are loaded
	 * from the image file given in the font file. The {@link #dispose()} method will not dispose the region's texture in this
	 * case!
	 * @param region The texture region containing the glyphs. The glyphs must be relative to the lower left corner (ie, the region
	 *           should not be flipped). If the region is null the glyph images are loaded from the image path in the font file.
	 * @param flip If true, the glyphs will be flipped for use with a perspective where 0,0 is the upper left corner. */
	public BitmapFont (FileHandle fontFile, TextureRegion region, boolean flip) {
		this(new BitmapFontData(fontFile, flip), region, true);
	}

	/** Creates a BitmapFont from a BMFont file. The image file name is read from the BMFont file and the image is loaded from the
	 * same directory. The font data is not flipped. */
	public BitmapFont (FileHandle fontFile) {
		this(fontFile, false);
	}

	/** Creates a BitmapFont from a BMFont file. The image file name is read from the BMFont file and the image is loaded from the
	 * same directory.
	 * @param flip If true, the glyphs will be flipped for use with a perspective where 0,0 is the upper left corner. */
	public BitmapFont (FileHandle fontFile, boolean flip) {
		this(new BitmapFontData(fontFile, flip), (TextureRegion)null, true);
	}

	/** Creates a BitmapFont from a BMFont file, using the specified image for glyphs. Any image specified in the BMFont file is
	 * ignored.
	 * @param flip If true, the glyphs will be flipped for use with a perspective where 0,0 is the upper left corner. */
	public BitmapFont (FileHandle fontFile, FileHandle imageFile, boolean flip) {
		this(fontFile, imageFile, flip, true);
	}

	/** Creates a BitmapFont from a BMFont file, using the specified image for glyphs. Any image specified in the BMFont file is
	 * ignored.
	 * @param flip If true, the glyphs will be flipped for use with a perspective where 0,0 is the upper left corner.
	 * @param integer If true, rendering positions will be at integer values to avoid filtering artifacts. */
	public BitmapFont (FileHandle fontFile, FileHandle imageFile, boolean flip, boolean integer) {
		this(new BitmapFontData(fontFile, flip), new TextureRegion(new Texture(imageFile, false)), integer);
		ownsTexture = true;
	}

	/** Constructs a new BitmapFont from the given {@link BitmapFontData} and {@link TextureRegion}. If the TextureRegion is null,
	 * the image path(s) will be read from the BitmapFontData. The dispose() method will not dispose the texture of the region(s)
	 * if the region is != null.
	 * <p>
	 * Passing a single TextureRegion assumes that your font only needs a single texture page. If you need to support multiple
	 * pages, either let the Font read the images themselves (by specifying null as the TextureRegion), or by specifying each page
	 * manually with the TextureRegion[] constructor.
	 * @param integer If true, rendering positions will be at integer values to avoid filtering artifacts. */
	public BitmapFont (BitmapFontData data, TextureRegion region, boolean integer) {
		this(data, region != null ? new TextureRegion[] {region} : null, integer);
	}

	/** Constructs a new BitmapFont from the given {@link BitmapFontData} and array of {@link TextureRegion}. If the TextureRegion
	 * is null or empty, the image path(s) will be read from the BitmapFontData. The dispose() method will not dispose the texture
	 * of the region(s) if the regions array is != null and not empty.
	 * @param integer If true, rendering positions will be at integer values to avoid filtering artifacts. */
	public BitmapFont (BitmapFontData data, TextureRegion[] regions, boolean integer) {
		if (regions == null || regions.length == 0) {
			// Load each path.
			this.regions = new TextureRegion[data.imagePaths.length];
			for (int i = 0; i < this.regions.length; i++) {
				if (data.fontFile == null) {
					this.regions[i] = new TextureRegion(new Texture(Gdx.files.internal(data.imagePaths[i]), false));
				} else {
					this.regions[i] = new TextureRegion(new Texture(Gdx.files.getFileHandle(data.imagePaths[i], data.fontFile.type()),
						false));
				}
			}
			ownsTexture = true;
		} else {
			this.regions = regions;
			ownsTexture = false;
		}

		cache = new BitmapFontCache(this);
		cache.setUseIntegerPositions(integer);

		this.flipped = data.flipped;
		this.data = data;
		this.integer = integer;
		load(data);
	}

	private void load (BitmapFontData data) {
		for (Glyph[] page : data.glyphs) {
			if (page == null) continue;
			for (Glyph glyph : page) {
				if (glyph == null) continue;

				TextureRegion region = regions[glyph.page];

				if (region == null) {
					// TODO: support null regions by parsing scaleW / scaleH ?
					throw new IllegalArgumentException("BitmapFont texture region array cannot contain null elements.");
				}

				float invTexWidth = 1.0f / region.getTexture().getWidth();
				float invTexHeight = 1.0f / region.getTexture().getHeight();

				float offsetX = 0, offsetY = 0;
				float u = region.u;
				float v = region.v;
				float regionWidth = region.getRegionWidth();
				float regionHeight = region.getRegionHeight();
				if (region instanceof AtlasRegion) {
					// Compensate for whitespace stripped from left and top edges.
					AtlasRegion atlasRegion = (AtlasRegion)region;
					offsetX = atlasRegion.offsetX;
					offsetY = atlasRegion.originalHeight - atlasRegion.packedHeight - atlasRegion.offsetY;
				}

				float x = glyph.srcX;
				float x2 = glyph.srcX + glyph.width;
				float y = glyph.srcY;
				float y2 = glyph.srcY + glyph.height;

				// Shift glyph for left and top edge stripped whitespace. Clip glyph for right and bottom edge stripped whitespace.
				if (offsetX > 0) {
					x -= offsetX;
					if (x < 0) {
						glyph.width += x;
						glyph.xoffset -= x;
						x = 0;
					}
					x2 -= offsetX;
					if (x2 > regionWidth) {
						glyph.width -= x2 - regionWidth;
						x2 = regionWidth;
					}
				}
				if (offsetY > 0) {
					y -= offsetY;
					if (y < 0) {
						glyph.height += y;
						y = 0;
					}
					y2 -= offsetY;
					if (y2 > regionHeight) {
						float amount = y2 - regionHeight;
						glyph.height -= amount;
						glyph.yoffset += amount;
						y2 = regionHeight;
					}
				}

				glyph.u = u + x * invTexWidth;
				glyph.u2 = u + x2 * invTexWidth;
				if (data.flipped) {
					glyph.v = v + y * invTexHeight;
					glyph.v2 = v + y2 * invTexHeight;
				} else {
					glyph.v2 = v + y * invTexHeight;
					glyph.v = v + y2 * invTexHeight;
				}
			}
		}
	}

	/** Draws text at the specified position.
	 * @see BitmapFontCache#addText(CharSequence, float, float) */
	public GlyphLayout draw (Batch batch, CharSequence str, float x, float y) {
		cache.clear();
		GlyphLayout layout = cache.addText(str, x, y);
		cache.draw(batch);
		return layout;
	}

	/** Draws text at the specified position.
	 * @see BitmapFontCache#addText(CharSequence, float, float, int, int, float, int, boolean) */
	public GlyphLayout draw (Batch batch, CharSequence str, float x, float y, float targetWidth, int halign, boolean wrap) {
		cache.clear();
		GlyphLayout layout = cache.addText(str, x, y, targetWidth, halign, wrap);
		cache.draw(batch);
		return layout;
	}

	/** Draws text at the specified position.
	 * @see BitmapFontCache#addText(CharSequence, float, float, int, int, float, int, boolean) */
	public GlyphLayout draw (Batch batch, CharSequence str, float x, float y, int start, int end, float targetWidth, int halign,
		boolean wrap) {
		cache.clear();
		GlyphLayout layout = cache.addText(str, x, y, start, end, targetWidth, halign, wrap);
		cache.draw(batch);
		return layout;
	}

	/** Draws text at the specified position.
	 * @see BitmapFontCache#addText(CharSequence, float, float, int, int, float, int, boolean) */
	public void draw (Batch batch, GlyphLayout layout, float x, float y) {
		cache.clear();
		cache.addText(layout, x, y);
		cache.draw(batch);
	}

	/** @deprecated Use {@link GlyphLayout} directly to avoid computing the glyphs twice. */
	public TextBounds getBounds (CharSequence str) {
		Pool<GlyphLayout> pool = Pools.get(GlyphLayout.class);
		GlyphLayout layout = pool.obtain();
		layout.setText(this, str);
		bounds.width = layout.width;
		bounds.height = layout.height;
		return bounds;
	}

	/** Computes the glyph advances for the given character sequence and stores them in the provided {@link FloatArray}. The float
	 * arrays are cleared. An additional element is added at the end.
	 * @param glyphAdvances the glyph advances output array.
	 * @param glyphPositions the glyph positions output array. */
	public void computeGlyphAdvancesAndPositions (CharSequence str, FloatArray glyphAdvances, FloatArray glyphPositions) {
		// BOZO - Replace method with GlyphLayout.
		glyphAdvances.clear();
		glyphPositions.clear();
		int index = 0;
		int end = str.length();
		float width = 0;
		Glyph lastGlyph = null;
		BitmapFontData data = this.data;
		if (data.scaleX == 1) {
			for (; index < end; index++) {
				char ch = str.charAt(index);
				Glyph g = data.getGlyph(ch);
				if (g != null) {
					if (lastGlyph != null) width += lastGlyph.getKerning(ch);
					lastGlyph = g;
					glyphAdvances.add(g.xadvance);
					glyphPositions.add(width);
					width += g.xadvance;
				}
			}
			glyphAdvances.add(0);
			glyphPositions.add(width);
		} else {
			float scaleX = this.data.scaleX;
			for (; index < end; index++) {
				char ch = str.charAt(index);
				Glyph g = data.getGlyph(ch);
				if (g != null) {
					if (lastGlyph != null) width += lastGlyph.getKerning(ch) * scaleX;
					lastGlyph = g;
					float xadvance = g.xadvance * scaleX;
					glyphAdvances.add(xadvance);
					glyphPositions.add(width);
					width += xadvance;
				}
			}
			glyphAdvances.add(0);
			glyphPositions.add(width);
		}
	}

	/** Returns the color of text drawn with this font. */
	public Color getColor () {
		return cache.getColor();
	}

	/** A convenience method for setting the font color. The color can also be set by modifying {@link #getColor()}. */
	public void setColor (Color color) {
		cache.getColor().set(color);
	}

	/** A convenience method for setting the font color. The color can also be set by modifying {@link #getColor()}. */
	public void setColor (float r, float g, float b, float a) {
		cache.getColor().set(r, g, b, a);
	}

	/** Scales the font by the specified amounts on both axes
	 * <p>
	 * Note that smoother scaling can be achieved if the texture backing the BitmapFont is using {@link TextureFilter#Linear}. The
	 * default is Nearest, so use a BitmapFont constructor that takes a {@link TextureRegion}.
	 * @throws IllegalArgumentException if scaleX or scaleY is zero. */
	public void setScale (float scaleX, float scaleY) {
		if (scaleX == 0) throw new IllegalArgumentException("scaleX cannot be 0.");
		if (scaleY == 0) throw new IllegalArgumentException("scaleY cannot be 0.");
		BitmapFontData data = this.data;
		float x = scaleX / data.scaleX;
		float y = scaleY / data.scaleY;
		data.lineHeight = data.lineHeight * y;
		data.spaceWidth = data.spaceWidth * x;
		data.xHeight = data.xHeight * y;
		data.capHeight = data.capHeight * y;
		data.ascent = data.ascent * y;
		data.descent = data.descent * y;
		data.down = data.down * y;
		data.scaleX = scaleX;
		data.scaleY = scaleY;
	}

	/** Scales the font by the specified amount in both directions.
	 * @see #setScale(float, float)
	 * @throws IllegalArgumentException if scaleX or scaleY is zero. */
	public void setScale (float scaleXY) {
		setScale(scaleXY, scaleXY);
	}

	/** Sets the font's scale relative to the current scale.
	 * @see #setScale(float, float)
	 * @throws IllegalArgumentException if the resulting scale is zero. */
	public void scale (float amount) {
		setScale(data.scaleX + amount, data.scaleY + amount);
	}

	public float getScaleX () {
		return data.scaleX;
	}

	public float getScaleY () {
		return data.scaleY;
	}

	/** Returns the first texture region. This is included for backwards compatibility, and for convenience since most fonts only
	 * use one texture page. For multi-page fonts, use {@link #getRegions()}.
	 * @return the first texture region */
	public TextureRegion getRegion () {
		return regions[0];
	}

	/** Returns the array of TextureRegions that represents each texture page of glyphs.
	 * @return the array of texture regions; modifying it may produce undesirable results */
	public TextureRegion[] getRegions () {
		return regions;
	}

	/** Returns the texture page at the given index.
	 * @return the texture page at the given index */
	public TextureRegion getRegion (int index) {
		return regions[index];
	}

	/** Returns the line height, which is the distance from one line of text to the next. */
	public float getLineHeight () {
		return data.lineHeight;
	}

	/** Returns the width of the space character. */
	public float getSpaceWidth () {
		return data.spaceWidth;
	}

	/** Returns the x-height, which is the distance from the top of most lowercase characters to the baseline. */
	public float getXHeight () {
		return data.xHeight;
	}

	/** Returns the cap height, which is the distance from the top of most uppercase characters to the baseline. Since the drawing
	 * position is the cap height of the first line, the cap height can be used to get the location of the baseline. */
	public float getCapHeight () {
		return data.capHeight;
	}

	/** Returns the ascent, which is the distance from the cap height to the top of the tallest glyph. */
	public float getAscent () {
		return data.ascent;
	}

	/** Returns the descent, which is the distance from the bottom of the glyph that extends the lowest to the baseline. This number
	 * is negative. */
	public float getDescent () {
		return data.descent;
	}

	/** Returns true if this BitmapFont has been flipped for use with a y-down coordinate system. */
	public boolean isFlipped () {
		return flipped;
	}

	/** Disposes the texture used by this BitmapFont's region IF this BitmapFont created the texture. */
	public void dispose () {
		if (ownsTexture) {
			for (int i = 0; i < regions.length; i++)
				regions[i].getTexture().dispose();
		}
	}

	/** Makes the specified glyphs fixed width. This can be useful to make the numbers in a font fixed width. Eg, when horizontally
	 * centering a score or loading percentage text, it will not jump around as different numbers are shown. */
	public void setFixedWidthGlyphs (CharSequence glyphs) {
		BitmapFontData data = this.data;
		int maxAdvance = 0;
		for (int index = 0, end = glyphs.length(); index < end; index++) {
			Glyph g = data.getGlyph(glyphs.charAt(index));
			if (g != null && g.xadvance > maxAdvance) maxAdvance = g.xadvance;
		}
		for (int index = 0, end = glyphs.length(); index < end; index++) {
			Glyph g = data.getGlyph(glyphs.charAt(index));
			if (g == null) continue;
			g.xoffset += (maxAdvance - g.xadvance) / 2;
			g.xadvance = maxAdvance;
			g.kerning = null;
		}
	}

	/** Checks whether this BitmapFont data contains a given character. */
	public boolean containsCharacter (char character) {
		return data.getGlyph(character) != null;
	}

	/** Specifies whether to use integer positions or not. Default is to use them so filtering doesn't kick in as badly. */
	public void setUseIntegerPositions (boolean integer) {
		this.integer = integer;
		cache.setUseIntegerPositions(integer);
	}

	/** Checks whether this font uses integer positions for drawing. */
	public boolean usesIntegerPositions () {
		return integer;
	}

	/** For expert usage -- returns the BitmapFontCache used by this font, for rendering to a sprite batch. This can be used, for
	 * example, to manipulate glyph colors within a specific index.
	 * @return the bitmap font cache used by this font */
	public BitmapFontCache getCache () {
		return cache;
	}

	/** Gets the underlying {@link BitmapFontData} for this BitmapFont. */
	public BitmapFontData getData () {
		return data;
	}

	/** @return whether the texture is owned by the font, font disposes the texture itself if true */
	public boolean ownsTexture () {
		return ownsTexture;
	}

	/** Sets whether the font owns the texture or not. In case it does, the font will also dispose of the texture when
	 * {@link #dispose()} is called. Use with care!
	 * @param ownsTexture whether the font owns the texture */
	public void setOwnsTexture (boolean ownsTexture) {
		this.ownsTexture = ownsTexture;
	}

	public String toString () {
		if (data.fontFile != null) {
			return data.fontFile.nameWithoutExtension();
		}
		return super.toString();
	}

	/** Represents a single character in a font page. */
	public static class Glyph {
		public int id;
		public int srcX;
		public int srcY;
		public int width, height;
		public float u, v, u2, v2;
		public int xoffset, yoffset;
		public int xadvance;
		public byte[][] kerning;

		/** The index to the texture page that holds this glyph. */
		public int page = 0;

		public int getKerning (char ch) {
			if (kerning != null) {
				byte[] page = kerning[ch >>> LOG2_PAGE_SIZE];
				if (page != null) return page[ch & PAGE_SIZE - 1];
			}
			return 0;
		}

		public void setKerning (int ch, int value) {
			if (kerning == null) kerning = new byte[PAGES][];
			byte[] page = kerning[ch >>> LOG2_PAGE_SIZE];
			if (page == null) kerning[ch >>> LOG2_PAGE_SIZE] = page = new byte[PAGE_SIZE];
			page[ch & PAGE_SIZE - 1] = (byte)value;
		}

		public String toString () {
			return Character.toString((char)id);
		}
	}

	static int indexOf (CharSequence text, char ch, int start) {
		final int n = text.length();
		for (; start < n; start++)
			if (text.charAt(start) == ch) return start;
		return n;
	}

	/** Container to hold text size.
	 * @deprecated Use {@link GlyphLayout} and/or store the values in a {@link Vector2}. */
	static public class TextBounds {
		public float width;
		public float height;

		public TextBounds () {
		}

		public TextBounds (TextBounds bounds) {
			set(bounds);
		}

		public void set (TextBounds bounds) {
			width = bounds.width;
			height = bounds.height;
		}
	}

	/** Backing data for a {@link BitmapFont}. */
	static public class BitmapFontData {
		/** An array of the image paths, for multiple texture pages. */
		public String[] imagePaths;
		public FileHandle fontFile;
		public boolean flipped;
		public float lineHeight;
		public float capHeight = 1;
		public float ascent;
		public float descent;
		public float down;
		public float scaleX = 1, scaleY = 1;
		public boolean markupEnabled;

		public final Glyph[][] glyphs = new Glyph[PAGES][];
		public float spaceWidth;
		public float xHeight = 1;

		/** Additional characters besides whitespace where text is wrapped. Eg, a hypen (-). */
		public char[] breakChars;

		/** Use this if you want to create BitmapFontData yourself, e.g. from stb-truetype or FreeType. */
		public BitmapFontData () {
		}

		@SuppressWarnings("deprecation")
		public BitmapFontData (FileHandle fontFile, boolean flip) {
			this.fontFile = fontFile;
			this.flipped = flip;
			BufferedReader reader = new BufferedReader(new InputStreamReader(fontFile.read()), 512);
			try {
				reader.readLine(); // info

				String line = reader.readLine();
				if (line == null) throw new GdxRuntimeException("File is empty.");
				String[] common = line.split(" ", 7); // At most we want the 6th element; i.e. "page=N"

				// At least lineHeight and base are required.
				if (common.length < 3) throw new GdxRuntimeException("Invalid header.");

				if (!common[1].startsWith("lineHeight=")) throw new GdxRuntimeException("Missing: lineHeight");
				lineHeight = Integer.parseInt(common[1].substring(11));

				if (!common[2].startsWith("base=")) throw new GdxRuntimeException("Missing: base");
				float baseLine = Integer.parseInt(common[2].substring(5));

				int pageCount = 1;
				if (common.length >= 6 && common[5] != null && common[5].startsWith("pages=")) {
					try {
						pageCount = Math.max(1, Integer.parseInt(common[5].substring(6)));
					} catch (NumberFormatException ignored) { // Use one page.
					}
				}

				imagePaths = new String[pageCount];

				// Read each page definition.
				for (int p = 0; p < pageCount; p++) {
					// Read each "page" info line.
					line = reader.readLine();
					if (line == null) throw new GdxRuntimeException("Missing additional page definitions.");
					String[] pageLine = line.split(" ", 4);
					if (!pageLine[2].startsWith("file=")) throw new GdxRuntimeException("Missing: file");

					// Expect ID to mean "index".
					if (pageLine[1].startsWith("id=")) {
						try {
							int pageID = Integer.parseInt(pageLine[1].substring(3));
							if (pageID != p)
								throw new GdxRuntimeException("Page IDs must be indices starting at 0: " + pageLine[1].substring(3));
						} catch (NumberFormatException ex) {
							throw new GdxRuntimeException("Invalid page id: " + pageLine[1].substring(3), ex);
						}
					}

					String fileName = null;
					if (pageLine[2].endsWith("\"")) {
						fileName = pageLine[2].substring(6, pageLine[2].length() - 1);
					} else {
						fileName = pageLine[2].substring(5, pageLine[2].length());
					}

					imagePaths[p] = fontFile.parent().child(fileName).path().replaceAll("\\\\", "/");
				}
				descent = 0;

				while (true) {
					line = reader.readLine();
					if (line == null) break; // EOF
					if (line.startsWith("kernings ")) break; // Starting kernings block.
					if (!line.startsWith("char ")) continue;

					Glyph glyph = new Glyph();

					StringTokenizer tokens = new StringTokenizer(line, " =");
					tokens.nextToken();
					tokens.nextToken();
					int ch = Integer.parseInt(tokens.nextToken());
					if (ch <= Character.MAX_VALUE)
						setGlyph(ch, glyph);
					else
						continue;
					glyph.id = ch;
					tokens.nextToken();
					glyph.srcX = Integer.parseInt(tokens.nextToken());
					tokens.nextToken();
					glyph.srcY = Integer.parseInt(tokens.nextToken());
					tokens.nextToken();
					glyph.width = Integer.parseInt(tokens.nextToken());
					tokens.nextToken();
					glyph.height = Integer.parseInt(tokens.nextToken());
					tokens.nextToken();
					glyph.xoffset = Integer.parseInt(tokens.nextToken());
					tokens.nextToken();
					if (flip)
						glyph.yoffset = Integer.parseInt(tokens.nextToken());
					else
						glyph.yoffset = -(glyph.height + Integer.parseInt(tokens.nextToken()));
					tokens.nextToken();
					glyph.xadvance = Integer.parseInt(tokens.nextToken());

					// Check for page safely, it could be omitted or invalid.
					if (tokens.hasMoreTokens()) tokens.nextToken();
					if (tokens.hasMoreTokens()) {
						try {
							glyph.page = Integer.parseInt(tokens.nextToken());
						} catch (NumberFormatException ignored) {
						}
					}

					if (glyph.width > 0 && glyph.height > 0) descent = Math.min(baseLine + glyph.yoffset, descent);
				}

				while (true) {
					line = reader.readLine();
					if (line == null) break;
					if (!line.startsWith("kerning ")) break;

					StringTokenizer tokens = new StringTokenizer(line, " =");
					tokens.nextToken();
					tokens.nextToken();
					int first = Integer.parseInt(tokens.nextToken());
					tokens.nextToken();
					int second = Integer.parseInt(tokens.nextToken());
					if (first < 0 || first > Character.MAX_VALUE || second < 0 || second > Character.MAX_VALUE) continue;
					Glyph glyph = getGlyph((char)first);
					tokens.nextToken();
					int amount = Integer.parseInt(tokens.nextToken());
					if (glyph != null) { // Kernings may exist for glyph pairs not contained in the font.
						glyph.setKerning(second, amount);
					}
				}

				Glyph spaceGlyph = getGlyph(' ');
				if (spaceGlyph == null) {
					spaceGlyph = new Glyph();
					spaceGlyph.id = (int)' ';
					Glyph xadvanceGlyph = getGlyph('l');
					if (xadvanceGlyph == null) xadvanceGlyph = getFirstGlyph();
					spaceGlyph.xadvance = xadvanceGlyph.xadvance;
					setGlyph(' ', spaceGlyph);
				}
				spaceWidth = spaceGlyph != null ? spaceGlyph.xadvance + spaceGlyph.width : 1;

				Glyph xGlyph = null;
				for (int i = 0; i < xChars.length; i++) {
					xGlyph = getGlyph(xChars[i]);
					if (xGlyph != null) break;
				}
				if (xGlyph == null) xGlyph = getFirstGlyph();
				xHeight = xGlyph.height;

				Glyph capGlyph = null;
				for (int i = 0; i < capChars.length; i++) {
					capGlyph = getGlyph(capChars[i]);
					if (capGlyph != null) break;
				}
				if (capGlyph == null) {
					for (Glyph[] page : this.glyphs) {
						if (page == null) continue;
						for (Glyph glyph : page) {
							if (glyph == null || glyph.height == 0 || glyph.width == 0) continue;
							capHeight = Math.max(capHeight, glyph.height);
						}
					}
				} else
					capHeight = capGlyph.height;

				ascent = baseLine - capHeight;
				down = -lineHeight;
				if (flip) {
					ascent = -ascent;
					down = -down;
				}
			} catch (Exception ex) {
				throw new GdxRuntimeException("Error loading font file: " + fontFile, ex);
			} finally {
				StreamUtils.closeQuietly(reader);
			}
		}

		/** Sets the line height, which is the distance from one line of text to the next. */
		public void setLineHeight (float height) {
			lineHeight = height * scaleY;
			down = flipped ? lineHeight : -lineHeight;
		}

		public void setGlyph (int ch, Glyph glyph) {
			Glyph[] page = glyphs[ch / PAGE_SIZE];
			if (page == null) glyphs[ch / PAGE_SIZE] = page = new Glyph[PAGE_SIZE];
			page[ch & PAGE_SIZE - 1] = glyph;
		}

		public Glyph getFirstGlyph () {
			for (Glyph[] page : this.glyphs) {
				if (page == null) continue;
				for (Glyph glyph : page) {
					if (glyph == null || glyph.height == 0 || glyph.width == 0) continue;
					return glyph;
				}
			}
			throw new GdxRuntimeException("No glyphs found.");
		}

		/** Returns the glyph for the specified character, or null if no such glyph exists. */
		public Glyph getGlyph (char ch) {
			Glyph[] page = glyphs[ch / PAGE_SIZE];
			if (page != null) return page[ch & PAGE_SIZE - 1];
			return null;
		}

		/** Using the specified string, populates the glyphs and positions of the specified glyph run.
		 * @param str Characters to convert to glyphs. Will not contain newline or color tags. May contain "[[" for an escaped left
		 *           square bracket. */
		public void getGlyphs (GlyphRun run, CharSequence str, int start, int end) {
			boolean markupEnabled = this.markupEnabled;
			float scaleX = this.scaleX;
			Array<Glyph> glyphs = run.glyphs;
			FloatArray xAdvances = run.xAdvances;

			Glyph lastGlyph = null;
			while (start < end) {
				char ch = str.charAt(start++);
				Glyph glyph = getGlyph(ch);
				if (glyph == null) continue;
				glyphs.add(glyph);

				if (lastGlyph != null) xAdvances.add((lastGlyph.xadvance + lastGlyph.getKerning(ch)) * scaleX);
				lastGlyph = glyph;

				// "[[" is an escaped left square bracket, skip second character.
				if (markupEnabled && ch == '[' && start < end && str.charAt(start) == '[') start++;
			}
			if (lastGlyph != null) xAdvances.add(lastGlyph.xadvance * scaleX);
		}

		public int getWrapIndex (Array<Glyph> glyphs, int start) {
			char ch = (char)glyphs.get(start).id;
			if (isWhitespace(ch)) return start + 1;
			for (int i = start - 1; i >= 1; i--) {
				ch = (char)glyphs.get(i).id;
				if (isWhitespace(ch)) return i + 1;
				if (isBreakChar(ch)) return i;
			}
			return start;
		}

		public boolean isBreakChar (char c) {
			if (breakChars == null) return false;
			for (char br : breakChars)
				if (c == br) return true;
			return false;
		}

		public boolean isWhitespace (char c) {
			switch (c) {
			case '\n':
			case '\r':
			case '\t':
			case ' ':
				return true;
			default:
				return false;
			}
		}

		/** Returns the image path for the texture page at the given index (the "id" in the BMFont file). */
		public String getImagePath (int index) {
			return imagePaths[index];
		}

		public String[] getImagePaths () {
			return imagePaths;
		}

		public FileHandle getFontFile () {
			return fontFile;
		}
	}
}
