import unittest
import tempfile
from pathlib import Path
import json,csv
from summarize import percentile,summarize_run

class ReportSemanticsTest(unittest.TestCase):
    def test_quantiles_use_nearest_rank_on_raw_samples(self):
        self.assertEqual(95,percentile(list(range(1,101)),.95))
        self.assertEqual(99,percentile(list(range(1,101)),.99))
        self.assertIsNone(percentile([],.95))

    def test_jmh_sample_histogram_does_not_require_throughput_raw_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary);fork=directory/'fork-1';fork.mkdir()
            (fork/'jmh.json').write_text(json.dumps([{'mode':'sample','threads':16,'params':{'payload':'128'},'primaryMetric':{'score':1,'scoreUnit':'ms/op','rawDataHistogram':[[[[1,3]]]],'scorePercentiles':{'95.0':2}}}]))
            result=summarize_run(directory,{'status':'passed','parameters':{'kind':'jmh','mode':'shared'}})
            self.assertEqual({'95.0':2},result['measurements'][0]['percentiles'])
            self.assertIsNone(result['measurements'][0]['raw_data'])
            self.assertEqual([[[[1,3]]]],result['measurements'][0]['raw_data_histogram'])

    def test_late_completion_throughput_differs_from_phase_cohort_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary)
            stats={'phase_seconds':[0,1,1,0],'scheduled':3,'writer_dropped':0,'pending_end':0,'timer_tasks_end':0,'client_project_threads_alive':0}
            (directory/'client-statistics.json').write_text(json.dumps(stats))
            (directory/'scenario.csv').write_text('service,phase,request_id,planned_ns,actual_ns,completed_ns,status,latency_ns,scheduling_ns,inflight,pending\nfast,healthy,a,800000000,800000000,1200000000,success,400000000,0,1,0\nfast,overload,b,1400000000,1400000000,1500000000,success,100000000,0,1,0\nfast,overload,c,1600000000,1600000000,1601000000,overloaded,1000000,0,1,0\n')
            (directory/'metrics.csv').write_text('service,queued,queue_capacity\nfast,0,1\nslow,1,1\n')
            manifest={'status':'passed','parameters':{'kind':'scenario','mode':'shared'}}
            result=summarize_run(directory,manifest)
            healthy=next(c for c in result['cohorts'] if c['phase']=='healthy')
            overload=next(c for c in result['cohorts'] if c['phase']=='overload')
            self.assertEqual(1,healthy['success_rate']);self.assertEqual(0,healthy['success_qps'])
            self.assertEqual(.5,overload['success_rate']);self.assertEqual(2,overload['success_qps'])
            self.assertEqual(100,overload['p95_ms']);self.assertEqual(1,overload['failure_p95_ms'])
            stats['scheduled']=4;(directory/'client-statistics.json').write_text(json.dumps(stats))
            with self.assertRaises(ValueError):summarize_run(directory,manifest)

if __name__=='__main__':unittest.main()
