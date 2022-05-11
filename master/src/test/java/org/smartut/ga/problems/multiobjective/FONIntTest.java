/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * Copyright (C) 2021- SmartUt contributors
 *
 * SmartUt is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * SmartUt is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with SmartUt. If not, see <http://www.gnu.org/licenses/>.
 */
package org.smartut.ga.problems.multiobjective;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.smartut.Properties;
import org.smartut.ga.ChromosomeFactory;
import org.smartut.ga.FitnessFunction;
import org.smartut.ga.NSGAChromosome;
import org.smartut.ga.metaheuristics.GeneticAlgorithm;
import org.smartut.ga.metaheuristics.NSGAII;
import org.smartut.ga.metaheuristics.RandomFactory;
import org.smartut.ga.operators.crossover.SBXCrossover;
import org.smartut.ga.operators.selection.BinaryTournamentSelectionCrowdedComparison;
import org.smartut.ga.problems.Problem;
import org.smartut.ga.problems.metrics.GenerationalDistance;
import org.smartut.ga.problems.metrics.Metrics;
import org.smartut.ga.problems.metrics.Spacing;
import org.smartut.ga.variables.DoubleVariable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 
 * @author José Campos
 */
public class FONIntTest
{
    @Before
    public void setUp() {
        Properties.POPULATION = 100;
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXGENERATIONS;
        Properties.SEARCH_BUDGET = 10_000;
        Properties.CROSSOVER_RATE = 0.9;
        Properties.RANDOM_SEED = 1L;
    }

    @Test
    public void testFONFitnesses()
    {
        Problem<NSGAChromosome> p = new FON();
        FitnessFunction<NSGAChromosome> f1 = p.getFitnessFunctions().get(0);
        FitnessFunction<NSGAChromosome> f2 = p.getFitnessFunctions().get(1);

        double[] values = {-2.0, 1.0, 3.0};
        NSGAChromosome c = new NSGAChromosome(-4.0, 4.0, values);
        Assert.assertEquals(((DoubleVariable) c.getVariables().get(0)).getValue(), -2.0, 0.0);
        Assert.assertEquals(((DoubleVariable) c.getVariables().get(1)).getValue(), 1.0, 0.0);
        Assert.assertEquals(((DoubleVariable) c.getVariables().get(2)).getValue(), 3.0, 0.0);

        Assert.assertEquals(f1.getFitness(c), 0.9999969200553233, 0.0);
        Assert.assertEquals(f2.getFitness(c), 0.9999999696175615, 0.0);
    }

    /**
     * Testing NSGA-II with FON Problem
     * 
     * @throws IOException 
     * @throws NumberFormatException 
     */
    @Test
    public void testFON() throws NumberFormatException, IOException
    {
        Properties.MUTATION_RATE = 1d / 3d;

        ChromosomeFactory<NSGAChromosome> factory = new RandomFactory(false, 3, -4.0, 4.0);

        GeneticAlgorithm<NSGAChromosome> ga = new NSGAII<>(factory);
        BinaryTournamentSelectionCrowdedComparison<NSGAChromosome> ts =
                new BinaryTournamentSelectionCrowdedComparison<>();
        ga.setSelectionFunction(ts);
        ga.setCrossOverFunction(new SBXCrossover());

        Problem<NSGAChromosome> p = new FON();
        final FitnessFunction<NSGAChromosome> f1 = p.getFitnessFunctions().get(0);
        final FitnessFunction<NSGAChromosome> f2 = p.getFitnessFunctions().get(1);
        ga.addFitnessFunction(f1);
        ga.addFitnessFunction(f2);

        // execute
        ga.generateSolution();

        List<NSGAChromosome> chromosomes = new ArrayList<>(ga.getPopulation());
        chromosomes.sort(Comparator.comparingDouble(chr -> chr.getFitness(f1)));

        double[][] front = new double[Properties.POPULATION][2];
        int index = 0;

        for (NSGAChromosome chromosome : chromosomes) {
            System.out.printf("%f,%f\n", chromosome.getFitness(f1), chromosome.getFitness(f2));
            front[index][0] = chromosome.getFitness(f1);
            front[index][1] = chromosome.getFitness(f2);

            index++;
        }

        // load True Pareto Front
        double[][] trueParetoFront = Metrics.readFront("Fonseca.pf");

        GenerationalDistance gd = new GenerationalDistance();
        double gdd = gd.evaluate(front, trueParetoFront);
        System.out.println("GenerationalDistance: " + gdd);
        Assert.assertTrue(gdd < 0.001);

        Spacing sp = new Spacing();
        double spd = sp.evaluate(front);
        double spdt = sp.evaluate(trueParetoFront);
        // compute difference between the generated front and a theoretical ideal front
        // a difference of 0 (or any value < 1 and close to 0) means that the generated
        // front is similar to a theoretical ideal front
        double diff = Math.abs(spd - spdt);
        System.out.println("SpacingFront (" + spd + ") - SpacingTrueFront (" + spdt + ") = " + diff);
        Assert.assertTrue(diff < 0.05);
    }
}
