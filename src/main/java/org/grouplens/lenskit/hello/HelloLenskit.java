/*
 * Copyright 2011 University of Minnesota
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.grouplens.lenskit.hello;

import com.google.common.base.Throwables;
import org.lenskit.LenskitConfiguration;
import org.lenskit.LenskitRecommender;
import org.lenskit.LenskitRecommenderEngine;
import org.lenskit.api.ItemRecommender;
import org.lenskit.api.Result;
import org.lenskit.api.ResultList;
import org.lenskit.config.ConfigHelpers;
import org.lenskit.data.dao.DataAccessObject;
import org.lenskit.data.dao.file.StaticDataSource;
import org.lenskit.data.entities.CommonAttributes;
import org.lenskit.data.entities.CommonTypes;
import org.lenskit.data.entities.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.lenskit.api.ItemScorer;
import org.lenskit.baseline.BaselineScorer;
import org.lenskit.baseline.ItemMeanRatingItemScorer;
import org.lenskit.baseline.UserMeanBaseline;
import org.lenskit.baseline.UserMeanItemScorer;
import org.lenskit.data.dao.file.DelimitedColumnEntityFormat;
import org.lenskit.data.dao.file.TextEntitySource;
import org.lenskit.data.entities.EntityType;
import org.lenskit.data.ratings.EntityCountRatingVectorPDAO;
import org.lenskit.data.ratings.RatingVectorPDAO;
import org.lenskit.knn.MinNeighbors;
import org.lenskit.knn.NeighborhoodSize;
import org.lenskit.knn.item.ItemItemScorer;
import org.lenskit.knn.item.ModelSize;
import org.lenskit.transform.normalize.UnitVectorNormalizer;
import org.lenskit.transform.normalize.UserVectorNormalizer;
import org.lenskit.transform.normalize.VectorNormalizer;

public class HelloLenskit implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(HelloLenskit.class);
    protected final EntityType LIKE = EntityType.forName("like");
    private Path dataFile = Paths.get("data/ratings2.csv");
    private List<Long> users;
    protected File inputFile;
//    protected StaticDataSource source;
    protected StaticDataSource implicitSource;

    public static void main(String[] args) {
        HelloLenskit hello = new HelloLenskit(args);
        try {
            hello.run();
        } catch (RuntimeException e) {
            System.err.println(e.toString());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }   

    public HelloLenskit(String[] args) {
        users = new ArrayList<>(args.length);
        for (String arg: args) {
            users.add(Long.parseLong(arg));
        }
    }

    public void run() {

        DelimitedColumnEntityFormat format = new DelimitedColumnEntityFormat();
        format.setDelimiter(",");
        format.setEntityType(LIKE);
        format.addColumns(CommonAttributes.USER_ID, CommonAttributes.ITEM_ID);
        TextEntitySource implicit = new TextEntitySource("likes"); //Entity reader that load entities from text data
        implicit.setFile(dataFile);
        implicit.setFormat(format);
        implicitSource = new StaticDataSource("implicit");
        implicitSource.addSource(implicit);
        implicitSource.addDerivedEntity(CommonTypes.USER, LIKE, CommonAttributes.USER_ID);
        implicitSource.addDerivedEntity(CommonTypes.ITEM, LIKE, CommonAttributes.ITEM_ID);

//        source = new StaticDataSource("ml-100k");
//        TextEntitySource tes = new TextEntitySource();
//        tes.setFile(dataFile);
//        tes.setFormat(org.lenskit.data.dao.file.Formats.tsvRatings());
//        source.addSource(tes);
        
        DataAccessObject dao = implicitSource.get();

        LenskitConfiguration config = new LenskitConfiguration();
        try {
            //config = ConfigHelpers.load(new File("etc/item-item.groovy"));
            config.bind(ItemScorer.class).to(ItemItemScorer.class);
            config.within(UserVectorNormalizer.class)
                .bind(VectorNormalizer.class)
                .to(UnitVectorNormalizer.class);
    
            config.bind(RatingVectorPDAO.class).to(EntityCountRatingVectorPDAO.class);
            config.set(EntityCountRatingVectorPDAO.CountedType.class).to(EntityType.forName("like"));
            config.bind(BaselineScorer.class, ItemScorer.class).to(UserMeanItemScorer.class);
            config.bind(UserMeanBaseline.class, ItemScorer.class).to(ItemMeanRatingItemScorer.class);
            config.set(MinNeighbors.class).to(2);
            config.set(ModelSize.class).to(1000);
        } catch (Exception e) {
            throw new RuntimeException("could not load configuration", e);
        }

       
        LenskitRecommenderEngine engine = LenskitRecommenderEngine.build(config, dao);
        logger.info("built recommender engine");


        try (LenskitRecommender rec = engine.createRecommender(dao)) {
            logger.info("obtained recommender from engine");
            // we want to recommend items
            ItemRecommender irec = rec.getItemRecommender();
            assert irec != null; // not null because we configured one
            // for users
            for (long user : users) {
                // get 10 recommendation for the user
                ResultList recs = irec.recommendWithDetails(user, 10, null, null);
                System.out.format("Recommendations for user %d:\n", user);
                for (Result item : recs) {
                    Entity itemData = dao.lookupEntity(CommonTypes.ITEM, item.getId());
                    String name = null;
                    if (itemData != null) {
                        name = itemData.maybeGet(CommonAttributes.NAME);
                    }
                    System.out.format("\t%d (%s): %.2f\n", item.getId(), name, item.getScore());
                }
            }
        }
    }
}
