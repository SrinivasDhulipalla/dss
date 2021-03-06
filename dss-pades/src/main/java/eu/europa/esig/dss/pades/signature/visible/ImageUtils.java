package eu.europa.esig.dss.pades.signature.visible;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import eu.europa.esig.dss.DSSDocument;
import eu.europa.esig.dss.DSSException;
import eu.europa.esig.dss.MimeType;
import eu.europa.esig.dss.pades.SignatureImageParameters;
import eu.europa.esig.dss.pades.SignatureImageTextParameters;
import eu.europa.esig.dss.utils.Utils;

/**
 * A static utilities that helps in creating ImageAndResolution
 * 
 * @author pakeyser
 *
 */
public class ImageUtils {

	private static final Logger LOG = LoggerFactory.getLogger(ImageUtils.class);

	private static final int DPI = 300;

	public static ImageAndResolution create(final SignatureImageParameters imageParameters) throws IOException {

		SignatureImageTextParameters textParamaters = imageParameters.getTextParameters();

		DSSDocument image = imageParameters.getImage();
		if ((textParamaters != null) && Utils.isStringNotEmpty(textParamaters.getText())) {

			BufferedImage buffImg = ImageTextWriter.createTextImage(textParamaters.getText(), textParamaters.getFont(), textParamaters.getTextColor(),
					textParamaters.getBackgroundColor(), DPI);

			if (image != null) {
				InputStream is = null;
				try {
					is = image.openStream();
					switch (textParamaters.getSignerNamePosition()) {
					case LEFT:
						buffImg = ImagesMerger.mergeOnRight(ImageIO.read(is), buffImg, textParamaters.getBackgroundColor());
						break;
					case RIGHT:
						buffImg = ImagesMerger.mergeOnRight(buffImg, ImageIO.read(is), textParamaters.getBackgroundColor());
						break;
					case TOP:
						buffImg = ImagesMerger.mergeOnTop(ImageIO.read(is), buffImg, textParamaters.getBackgroundColor());
						break;
					case BOTTOM:
						buffImg = ImagesMerger.mergeOnTop(buffImg, ImageIO.read(is), textParamaters.getBackgroundColor());
						break;
					default:
						break;
					}
				} finally {
					Utils.closeQuietly(is);
				}
			}
			return convertToInputStream(buffImg, DPI);
		}

		// Image only
		return readAndDisplayMetadata(image);
	}

	private static ImageAndResolution readAndDisplayMetadata(DSSDocument image) throws IOException {
		if (isImageWithContentType(image, MimeType.JPEG)) {
			return readAndDisplayMetadataJPEG(image);
		} else if (isImageWithContentType(image, MimeType.PNG)) {
			return readAndDisplayMetadataPNG(image);
		}
		throw new DSSException("Unsupported image type");
	}

	private static boolean isImageWithContentType(DSSDocument image, MimeType expectedContentType) {
		if (image.getMimeType() != null) {
			return expectedContentType == image.getMimeType();
		} else {
			String contentType = null;
			try {
				contentType = Files.probeContentType(Paths.get(image.getAbsolutePath()));
			} catch (IOException e) {
				LOG.warn("Unable to retrieve the content-type : " + e.getMessage());
			}
			return Utils.areStringsEqual(expectedContentType.getMimeTypeString(), contentType);
		}
	}

	public static ImageAndResolution readAndDisplayMetadataJPEG(DSSDocument image) throws IOException {

		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
		if (!readers.hasNext()) {
			throw new DSSException("No writer for JPEG found");
		}

		// pick the first available ImageReader
		ImageReader reader = readers.next();

		ImageInputStream iis = null;
		try {
			iis = ImageIO.createImageInputStream(image.openStream());

			// attach source to the reader
			reader.setInput(iis, true);

			// read metadata of first image
			IIOMetadata metadata = reader.getImageMetadata(0);

			Node asTree = metadata.getAsTree("javax_imageio_jpeg_image_1.0");
			ImageAndResolution res = readResolutionJPEG(asTree, image.openStream());
			return res;
		} finally {
			Utils.closeQuietly(iis);
		}
	}

	public static ImageAndResolution readAndDisplayMetadataPNG(DSSDocument image) throws IOException {

		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
		if (!readers.hasNext()) {
			throw new DSSException("No writer for PNG found");
		}

		// pick the first available ImageReader
		ImageReader reader = readers.next();

		ImageInputStream iis = null;
		try {
			iis = ImageIO.createImageInputStream(image.openStream());

			// attach source to the reader
			reader.setInput(iis, true);

			int hdpi = 96, vdpi = 96;
			double mm2inch = 25.4;

			NodeList lst;
			Element node = (Element) reader.getImageMetadata(0).getAsTree("javax_imageio_1.0");
			lst = node.getElementsByTagName("HorizontalPixelSize");
			if (lst != null && lst.getLength() == 1) {
				hdpi = (int) (mm2inch / Float.parseFloat(((Element) lst.item(0)).getAttribute("value")));
			}

			lst = node.getElementsByTagName("VerticalPixelSize");
			if (lst != null && lst.getLength() == 1) {
				vdpi = (int) (mm2inch / Float.parseFloat(((Element) lst.item(0)).getAttribute("value")));
			}

			return new ImageAndResolution(image.openStream(), hdpi, vdpi);
		} finally {
			Utils.closeQuietly(iis);
		}
	}

	private static ImageAndResolution readResolutionJPEG(Node node, InputStream is) {

		Element root = (Element) node;

		NodeList elements = root.getElementsByTagName("app0JFIF");

		Element e = (Element) elements.item(0);
		int x = Integer.parseInt(e.getAttribute("Xdensity"));
		int y = Integer.parseInt(e.getAttribute("Ydensity"));
		return new ImageAndResolution(is, x, y);
	}

	private static ImageAndResolution convertToInputStream(BufferedImage buffImage, int dpi) throws IOException {
		Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
		if (!it.hasNext()) {
			throw new DSSException("No writer for JPEG found");
		}
		ImageWriter writer = it.next();

		JPEGImageWriteParam jpegParams = (JPEGImageWriteParam) writer.getDefaultWriteParam();
		jpegParams.setCompressionMode(JPEGImageWriteParam.MODE_EXPLICIT);
		jpegParams.setCompressionQuality(1);

		ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB);
		IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier, jpegParams);

		initDpi(metadata, dpi);

		ByteArrayOutputStream os = new ByteArrayOutputStream();
		ImageOutputStream imageOs = ImageIO.createImageOutputStream(os);
		writer.setOutput(imageOs);
		writer.write(metadata, new IIOImage(buffImage, null, metadata), jpegParams);

		InputStream is = new ByteArrayInputStream(os.toByteArray());
		return new ImageAndResolution(is, dpi, dpi);
	}

	private static void initDpi(IIOMetadata metadata, int dpi) throws IIOInvalidTreeException {
		Element tree = (Element) metadata.getAsTree("javax_imageio_jpeg_image_1.0");
		Element jfif = (Element) tree.getElementsByTagName("app0JFIF").item(0);
		jfif.setAttribute("Xdensity", Integer.toString(dpi));
		jfif.setAttribute("Ydensity", Integer.toString(dpi));
		jfif.setAttribute("resUnits", "1"); // density is dots per inch
		metadata.setFromTree("javax_imageio_jpeg_image_1.0", tree);
	}

}
