package info.bethard.timenorm

import scala.collection.immutable.{ Seq, IndexedSeq }
import scala.collection.mutable
import org.threeten.bp.temporal.ChronoUnit
import org.threeten.bp.temporal.ChronoField
import scala.collection.mutable.ListBuffer

class SynchronousParser(grammar: SynchronousGrammar) {

  import SynchronousParser._
  
  def parseAll(sourceTokens: Array[String]): Array[Tree.NonTerminal] = {
    this.parseAll(sourceTokens.toIndexedSeq).toArray
  }

  def parseAll(sourceTokens: IndexedSeq[String]): IndexedSeq[Tree.NonTerminal] = {
    val chart = this.parseChart(sourceTokens)
    val completes = chart(sourceTokens.size)(0).completes
    val roots = completes.filter(parse => this.grammar.rootSymbols.contains(parse.rule.symbol))
    val trees = roots.map(_.toTargetTree).toIndexedSeq
    if (trees.isEmpty) {
      val nTokens = sourceTokens.size
      val completes =
        for {
          size <- 1 to nTokens
          start <- 0 until (nTokens - size + 1)
          complete <- chart(size)(start).completes
        } yield {
          "%s(%s)".format(complete.rule.symbol, sourceTokens.slice(start, start + size).mkString(","))
        }
      val message = "Could not parse %s. Partial parses: %s"
      throw new UnsupportedOperationException(message.format(sourceTokens, completes))
    }
    trees
  }

  private def parseChart(sourceTokens: IndexedSeq[String]): Array[Array[ChartEntry]] = {
    val nTokens = sourceTokens.size
    val chart = Array.tabulate(nTokens + 1, nTokens) {
      (size, start) => if (size == 0 || start + size > nTokens) null else ChartEntry()
    }

    // special handling of [Number]: pass through tokens that are numbers 
    for (start <- 0 until nTokens) {
      val token = sourceTokens(start)
      if (SynchronousGrammar.isNumber(token)) {
        for (symbol <- grammar.symbolsForNumber(token.toInt)) {
          val rule = SynchronousGrammar.Rule(symbol, IndexedSeq(token), IndexedSeq(token), Map.empty)
          chart(1)(start).completes += Parse(rule, IndexedSeq.empty)
        }
      }
    }

    // fill rules that start with terminals
    for (start <- 0 until nTokens) {
      for (rule <- grammar.sourceSeqStartsWith(sourceTokens(start))) {
        val initialTerminals = rule.sourceSeq.takeWhile(SynchronousGrammar.isTerminal)
        val size = initialTerminals.size
        if (sourceTokens.slice(start, start + size) == initialTerminals) {
          val entry = chart(size)(start)
          if (rule.sourceSeq.size == size) {
            entry.completes += Parse(rule, IndexedSeq.empty)
          } else {
            entry.partials += PartialParse(rule, size, IndexedSeq.empty)
          }
        }
      }
    }

    // fill in the chart from the smallest sizes to the biggest sizes
    for (size <- 1 to nTokens; start <- 0 to (nTokens - size)) {
      val entry = chart(size)(start)

      // look for ways to create entries of size `size` from the current partial parses
      for (size1 <- 1 until size) {
        val start2 = start + size1
        val size2 = size - size1
        for (partial <- chart(size1)(start).partials) {

          // partials that can be advanced to `size` using terminals
          val newSourceSeqIndex = partial.sourceSeqIndex + size2
          val symbolSeq = partial.rule.sourceSeq.slice(partial.sourceSeqIndex, newSourceSeqIndex)
          val tokenSeq = sourceTokens.slice(start2, start2 + size2)
          if (symbolSeq.forall(SynchronousGrammar.isTerminal) && symbolSeq == tokenSeq) {
            if (partial.rule.sourceSeq.size == newSourceSeqIndex) {
              entry.completes += Parse(partial.rule, partial.nonTerminalRules)
            } else {
              entry.partials += PartialParse(partial.rule, newSourceSeqIndex, partial.nonTerminalRules)
            }
          }

          // partials that can be advanced to `size` using completed non-terminals
          for (complete <- chart(size2)(start2).completes) {
            if (partial.rule.sourceSeq(partial.sourceSeqIndex) == complete.rule.symbol) {
              val sourceSeqIndex = partial.sourceSeqIndex + 1
              val nonTerminalRules = partial.nonTerminalRules :+ complete
              if (partial.rule.sourceSeq.size == sourceSeqIndex) {
                entry.completes += Parse(partial.rule, nonTerminalRules)
              } else {
                entry.partials += PartialParse(partial.rule, sourceSeqIndex, nonTerminalRules)
              }
            }
          }
        }

        // expand complete parses if there are Nil parses beside them
        for (complete1 <- chart(size1)(start).completes) {
          for (complete2 <- chart(size2)(start2).completes) {
            if (!complete1.rule.isNilSymbol && complete2.rule.isNilSymbol) {
              entry.completes += complete1
            } else if (complete1.rule.isNilSymbol && !complete2.rule.isNilSymbol) {
              entry.completes += complete2            
            }
          }
        }
      }
      
      // create parses for rules that start with any of the currently complete parses
      // NOTE: we have to use a queue here because the loop itself may add more completed
      // rules that we then also need to process
      val queue = mutable.Queue.empty ++ entry.completes
      while (queue.nonEmpty) {
        val complete = queue.dequeue
        for (rule <- grammar.sourceSeqStartsWith(complete.rule.symbol)) {
          if (rule.sourceSeq.tail.isEmpty) {
            val complete2 = Parse(rule, IndexedSeq(complete))
            queue.enqueue(complete2)
            entry.completes += complete2
          } else {
            entry.partials += PartialParse(rule, 1, IndexedSeq(complete))
          }
        }
      }
    }
    chart
  }
}

object SynchronousParser {
  
  sealed trait Tree
  object Tree {
    case class Terminal(token: String) extends Tree
    case class NonTerminal(basicSymbol: String, fullSymbol: String, children: List[Tree], rule: SynchronousGrammar.Rule) extends Tree
  }

  private[SynchronousParser] case class Parse(
      rule: SynchronousGrammar.Rule,
      nonTerminalRules: IndexedSeq[Parse]) {
    
    def toTargetTree: Tree.NonTerminal = {
      var nonTerminalIndex = -1
      val children = for ((token, i) <- this.rule.targetSeq.zipWithIndex) yield {
        if (SynchronousGrammar.isTerminal(token)) {
          Tree.Terminal(token)
        } else {
          nonTerminalIndex += 1
          val nonTerminalRulesIndex = this.rule.nonTerminalAlignment(nonTerminalIndex)
          this.nonTerminalRules(nonTerminalRulesIndex).toTargetTree
        }
      }
      val subtrees = this.insertSubtreesFromParentheses(children.iterator)
      Tree.NonTerminal(rule.basicSymbol, rule.symbol, subtrees, rule)
    }
    
    private def insertSubtreesFromParentheses(trees: Iterator[Tree]): List[Tree] = {
      if (trees.isEmpty) {
        Nil
      } else {
        val tree = trees.next match {
          case Tree.Terminal("(") => this.parseSubtreeFollowingOpenParentheses(trees)
          case tree => tree
        }
        tree :: this.insertSubtreesFromParentheses(trees)
      }
    }
    
    private def parseSubtreeFollowingOpenParentheses(trees: Iterator[Tree]): Tree = {
      val Tree.Terminal(symbol) = trees.next
      val children = ListBuffer.empty[Tree]
      var getNext = true
      while (getNext) {
        trees.next match {
          case Tree.Terminal(")") => getNext = false
          case Tree.Terminal("(") => children += this.parseSubtreeFollowingOpenParentheses(trees)
          case tree => children += tree
        }
      }
      val rule = SynchronousGrammar.Rule("[" + symbol + "]", IndexedSeq.empty, IndexedSeq.empty, Map.empty)
      Tree.NonTerminal(rule.basicSymbol, rule.symbol, children.toList, rule)
    }
  }

  private[SynchronousParser] case class PartialParse(
    rule: SynchronousGrammar.Rule,
    sourceSeqIndex: Int,
    nonTerminalRules: IndexedSeq[Parse])

  private[SynchronousParser] case class ChartEntry(
    completes: mutable.Set[Parse] = mutable.Set.empty,
    partials: mutable.Set[PartialParse] = mutable.Set.empty)
}
