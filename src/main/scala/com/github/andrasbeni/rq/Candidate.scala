package com.github.andrasbeni.rq

import java.util.Properties

import com.github.andrasbeni.rq.proto.{AppendEntriesReq, AppendEntriesResp, VoteReq, VoteResp}

object Candidate extends RoleFactory {
  override def apply(config : Properties, stateMachine: StateMachine, persistence: Persistence, cluster: Cluster, executor: Executor, roleListener : Role => Unit): Role = {
    new Candidate(config, stateMachine, persistence, cluster, executor, roleListener)
  }
}

class Candidate(config : Properties, stateMachine : StateMachine, persistence : Persistence, cluster : Cluster, executor : Executor, roleListener : Role => Unit)
  extends Role(config, stateMachine, persistence, cluster, executor, roleListener) {

  private var yes : Int = 1

  override def startRole(): Unit = {
    super.startRole()
    persistence.setVoteAndTerm(cluster.localId, persistence.voteAndTerm.term + 1)
    val lastEntry = persistence.log.lastEntry
    val req = new VoteReq(persistence.term, cluster.localId, lastEntry.getIndex, lastEntry.getTerm)
    for (node <- cluster.serverIds) {
      logger.debug(s"Sending vote request to $node: $req")
      cluster.clientTo(node).requestVote(req, new Hollaback[VoteResp](s"Could not send vote request to $node.", result => {

            logger.debug(s"Received vote result from $node: $result")
            if (!alive) {
              logger.debug(s"Ignoring late response from $node")
              return
            }
            if (result.getTerm >= persistence.term) {
              becomeFollower(node)
            } else if (result.getVoteGranted) {
              yes += 1
              if (yes > cluster.size / 2) {
                convertTo(Leader)
              }
            }
        }))
      logger.debug(s"Sent vote request to $node")
    }
    startTimer()
  }

  override def appendEntries(req: AppendEntriesReq): AppendEntriesResp = {
    val valid = req.getTerm >= persistence.term
    if (valid) {
      persistence.setVoteAndTerm(-1, req.getTerm)
      val nextRole = becomeFollower(req.getLeaderId)
      nextRole.appendEntries(req)
    } else {
      new AppendEntriesResp(persistence.term, false)
    }
  }

}
